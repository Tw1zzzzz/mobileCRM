import { readFileSync } from "node:fs";
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FACEIT_CACHE_TTL_MS, FAILURE_MEMORY_MS, loadMatches } from "../lib/faceit/cache";
import { REQUEST_TIMEOUT_MS } from "../lib/faceit/client";
import { FACEIT_PAGE_DEADLINE_MS, raceDeadline } from "../lib/faceit/deadline";
import { mapMatch, type FaceitMatch } from "../lib/faceit/mapper";
import { rateLimit } from "../lib/rateLimit";
import { gameSummary, matchDaily, UNAVAILABLE_TILES } from "../lib/staff/game";
import { resetDb, testDeps } from "./helpers";

interface Fixture {
  now: string;
  raw: unknown[];
  mapped: FaceitMatch[];
  tiles: Record<string, number | null>;
}
const fixture = JSON.parse(readFileSync(new URL("./fixtures/faceit-matches.json", import.meta.url), "utf8")) as Fixture;
const FIXTURE_NOW = new Date(fixture.now);

/** The payload of core's FaceitValidationTests.matchStatsBecomeGameMatches. */
const CORE_SAMPLE = {
  stats: {
    "Match Id": "1-6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b", Map: "de_inferno", Result: "1", Score: "13 / 9",
    Kills: "24", Deaths: "15", Assists: "5", ADR: "96.4", "Headshots %": "50", MVPs: "4", "First Kills": "3",
    "Entry Count": "5", "Entry Wins": "3", "1v1Wins": "1", "1v2Wins": "1", "Double Kills": "3", "Triple Kills": "1",
    "Utility Damage": "180", "Enemies Flashed": "7", "Sniper Kills": "2", Rounds: "22", "Match Finished At": 1760000000000,
  },
};

describe("mapMatch", () => {
  it("mapMatchMatchesCoreSample", () => {
    expect(mapMatch(CORE_SAMPLE, FIXTURE_NOW)).toEqual({
      id: "6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b",
      map: "de_inferno",
      won: true,
      scoreOwn: 13,
      scoreOther: 9,
      kills: 24,
      deaths: 15,
      assists: 5,
      adr: 96.4,
      rounds: 22,
      finishedAt: "2025-10-09T08:53:20.000Z",
      firstKills: 3,
      entryCount: 5,
      entryWins: 3,
      clutchWins: 2,
      multiKills: { k2: 3, k3: 1, k4: 0, k5: 0 },
      utilityDamage: 180,
      enemiesFlashed: 7,
    });
  });

  it("lossTakesLowerScore", () => {
    const m = mapMatch({ stats: { Result: "0", Score: "13 / 7", Kills: "10", Deaths: "18" } }, FIXTURE_NOW);
    expect(m).not.toBeNull();
    expect(m!.won).toBe(false);
    expect([m!.scoreOwn, m!.scoreOther]).toEqual([7, 13]);
    expect(m!.rounds).toBe(20);
  });

  it("skipsItemsWithoutKillsOrDeaths", () => {
    expect(mapMatch({ stats: { Map: "de_nuke", Result: "0" } }, FIXTURE_NOW)).toBeNull();
    expect(mapMatch({ stats: { Result: "0", Deaths: "10" } }, FIXTURE_NOW)).toBeNull();
    expect(mapMatch({ stats: { Result: "1", Kills: "9" } }, FIXTURE_NOW)).toBeNull();
    expect(mapMatch({ stats: { Kills: "abc", Deaths: "3" } }, FIXTURE_NOW)).toBeNull();
    for (const bad of [null, undefined, "x", 7, [], {}, { stats: null }, { stats: [] }]) {
      expect(mapMatch(bad, FIXTURE_NOW)).toBeNull();
    }
  });

  it("looseNumbersAcceptStringsAndCommas", () => {
    const m = mapMatch({ stats: { Kills: "17,6", Deaths: " 12,5 ", Assists: "3", ADR: "87,25", Result: "1", Score: "16 / 14" } }, FIXTURE_NOW)!;
    expect([m.kills, m.deaths, m.assists]).toEqual([18, 13, 3]);
    expect(m.adr).toBe(87.25);
    expect(m.rounds).toBe(30);
    // a number that is not text or a number counts as missing
    expect(mapMatch({ stats: { Kills: true, Deaths: "3" } }, FIXTURE_NOW)).toBeNull();
    expect(mapMatch({ stats: { Kills: "1.2.3", Deaths: "3" } }, FIXTURE_NOW)).toBeNull();
  });

  it("secondsAndMillisecondsGiveTheSameInstant", () => {
    const base = { Kills: "1", Deaths: "1" };
    const ms = mapMatch({ stats: { ...base, "Match Finished At": 1760000000000 } }, FIXTURE_NOW)!;
    const sec = mapMatch({ stats: { ...base, "Match Finished At": 1760000000 } }, FIXTURE_NOW)!;
    const text = mapMatch({ stats: { ...base, "Match Finished At": "1760000000" } }, FIXTURE_NOW)!;
    expect(ms.finishedAt).toBe("2025-10-09T08:53:20.000Z");
    expect(sec.finishedAt).toBe(ms.finishedAt);
    expect(text.finishedAt).toBe(ms.finishedAt);
  });

  it("timestampFallsBackToUpdatedThenCreatedThenNow", () => {
    const base = { Kills: "1", Deaths: "1" };
    expect(mapMatch({ stats: { ...base, "Updated At": 1759800000000, "Created At": 1 } }, FIXTURE_NOW)!.finishedAt).toBe("2025-10-07T01:20:00.000Z");
    expect(mapMatch({ stats: { ...base, "Created At": 1759700000 } }, FIXTURE_NOW)!.finishedAt).toBe("2025-10-05T21:33:20.000Z");
    expect(mapMatch({ stats: base }, FIXTURE_NOW)!.finishedAt).toBe(FIXTURE_NOW.toISOString());
  });

  it("scoreAndRoundsFallbacks", () => {
    // no score: 0:0 rounds, Rounds from the payload
    const noScore = mapMatch({ stats: { Result: "0", Kills: "8", Deaths: "14", Rounds: "26" } }, FIXTURE_NOW)!;
    expect([noScore.scoreOwn, noScore.scoreOther, noScore.rounds]).toEqual([0, 0, 26]);
    // neither: at least one round
    expect(mapMatch({ stats: { Kills: "1", Deaths: "1" } }, FIXTURE_NOW)!.rounds).toBe(1);
    // no slash, three parts: unreadable score
    expect(mapMatch({ stats: { Kills: "1", Deaths: "1", Score: "13-9" } }, FIXTURE_NOW)!.scoreOwn).toBe(0);
    expect(mapMatch({ stats: { Kills: "1", Deaths: "1", Score: "1 / 2 / 3" } }, FIXTURE_NOW)!.scoreOther).toBe(0);
    // tight spacing, and Result as a number
    const tight = mapMatch({ stats: { Kills: "1", Deaths: "1", Result: 1, Score: "13/ 2" } }, FIXTURE_NOW)!;
    expect([tight.won, tight.scoreOwn, tight.scoreOther]).toEqual([true, 13, 2]);
  });

  it("matchIdIsStrippedLowercasedOrKept", () => {
    const base = { Kills: "1", Deaths: "1", "Match Finished At": 1760000000 };
    expect(mapMatch({ stats: { ...base, "Match Id": "1-AABBCCDD-1111-4222-8333-444455556666" } }, FIXTURE_NOW)!.id).toBe("aabbccdd-1111-4222-8333-444455556666");
    expect(mapMatch({ stats: { ...base, "Match Id": "1-not-a-uuid" } }, FIXTURE_NOW)!.id).toBe("not-a-uuid");
    // no id: a stable id made from the match itself, equal for equal input
    const a = mapMatch({ stats: base }, FIXTURE_NOW)!;
    const b = mapMatch({ stats: base }, FIXTURE_NOW)!;
    expect(a.id).toBeTruthy();
    expect(a.id).toBe(b.id);
  });

  it("optionalDetailsAreOmittedWhenAbsent", () => {
    const m = mapMatch({ stats: { Kills: "5", Deaths: "5", "Match Finished At": 1760000000 } }, FIXTURE_NOW)!;
    for (const key of ["firstKills", "entryCount", "entryWins", "clutchWins", "multiKills", "utilityDamage", "enemiesFlashed"]) {
      expect(m).not.toHaveProperty(key);
    }
    const partial = mapMatch({ stats: { Kills: "5", Deaths: "5", "1v2Wins": "1", "Penta Kills": "1" } }, FIXTURE_NOW)!;
    expect(partial.clutchWins).toBe(1);
    expect(partial.multiKills).toEqual({ k2: 0, k3: 0, k4: 0, k5: 1 });
  });

  it("fixtureRawItemsMapToTheFrozenExpectations", () => {
    const mapped = fixture.raw.map((item) => mapMatch(item, FIXTURE_NOW)).filter((m): m is FaceitMatch => m !== null);
    expect(mapped).toHaveLength(fixture.mapped.length);
    mapped.forEach((m, i) => {
      const want = fixture.mapped[i];
      expect(m, `match ${i}`).toEqual({ ...want, adr: expect.closeTo(want.adr, 9) });
    });
  });
});

describe("gameSummary", () => {
  const ALL_FROM = "2025-10-01";
  const ALL_TO = "2026-10-31";

  it("gameSummaryTilesAndNulls", () => {
    const tiles = gameSummary(fixture.mapped, ALL_FROM, ALL_TO, "Europe/Moscow") as unknown as Record<string, number | null>;
    for (const [key, want] of Object.entries(fixture.tiles)) {
      if (want === null) expect(tiles[key], key).toBeNull();
      else expect(tiles[key], key).toBeCloseTo(want, 9);
    }
    // the tiles Faceit cannot give are null, never zero
    expect(UNAVAILABLE_TILES).toEqual(["kast", "firstDeaths", "avgFlashTime", "draws", "pistolWon", "pistolRounds", "pistolWinRate"]);
    for (const key of UNAVAILABLE_TILES) expect(tiles[key], key).toBeNull();
  });

  it("noMatchesInTheWindowMeansNoData", () => {
    const tiles = gameSummary(fixture.mapped, "2020-01-01", "2020-01-31", "Europe/Moscow") as unknown as Record<string, number | null>;
    expect(tiles.matches).toBe(0);
    for (const [key, value] of Object.entries(tiles)) {
      if (key !== "matches") expect(value, key).toBeNull();
    }
    expect(gameSummary([], ALL_FROM, ALL_TO, "UTC").matches).toBe(0);
  });

  it("windowUsesTheCalendarDayInTheStaffZone", () => {
    const late = match({ id: "late", finishedAt: "2026-10-30T21:30:00.000Z" }); // 31 Oct 00:30 in Moscow
    const early = match({ id: "early", finishedAt: "2026-10-31T20:59:59.999Z" }); // 31 Oct 23:59 in Moscow
    const next = match({ id: "next", finishedAt: "2026-10-31T21:00:00.000Z" }); // 1 Nov 00:00 in Moscow
    const list = [late, early, next];
    expect(gameSummary(list, "2026-10-31", "2026-10-31", "Europe/Moscow").matches).toBe(2);
    expect(gameSummary(list, "2026-10-31", "2026-10-31", "UTC").matches).toBe(2); // early + next
    expect(gameSummary(list, "2026-10-30", "2026-10-30", "UTC").matches).toBe(1); // late
    expect(gameSummary(list, "2026-11-01", "2026-11-01", "Europe/Moscow").matches).toBe(1);
  });

  it("optionalTilesNeedAtLeastOneMatchWithTheField", () => {
    const bare = [match({ id: "a" }), match({ id: "b" })];
    const tiles = gameSummary(bare, ALL_FROM, ALL_TO, "UTC");
    expect(tiles.firstKills).toBeNull();
    expect(tiles.udr).toBeNull();
    expect(tiles.openDuelDiff).toBeNull();
    expect(tiles.avgMultikills).toBeNull();
    expect(tiles.clutchesWon).toBeNull();
    expect(tiles.kills).toBe(40);
    const some = [match({ id: "a" }), match({ id: "b", firstKills: 2, entryCount: 3, entryWins: 1, clutchWins: 1, multiKills: { k2: 1, k3: 1, k4: 0, k5: 0 }, utilityDamage: 100, rounds: 25 })];
    const withSome = gameSummary(some, ALL_FROM, ALL_TO, "UTC");
    expect(withSome.firstKills).toBe(2);
    expect(withSome.openDuelDiff).toBe(-1); // 1 won, 2 lost
    expect(withSome.clutchesWon).toBe(1);
    expect(withSome.avgMultikills).toBe(2); // 2 multi-kill rounds over the one match that has the field
    expect(withSome.udr).toBe(4); // 100 damage over its 25 rounds
  });
});

describe("matchDaily", () => {
  it("matchDailyMeans", () => {
    const list = [
      match({ id: "1", finishedAt: "2026-10-30T10:00:00.000Z", kills: 20, deaths: 10, adr: 90, won: true }),
      match({ id: "2", finishedAt: "2026-10-30T15:00:00.000Z", kills: 10, deaths: 20, adr: 50, won: false }),
      match({ id: "3", finishedAt: "2026-10-30T18:00:00.000Z", kills: 15, deaths: 0, adr: 100, won: true }),
      match({ id: "4", finishedAt: "2026-10-31T09:00:00.000Z", kills: 12, deaths: 12, adr: 70, won: false }),
      match({ id: "5", finishedAt: "2026-10-28T09:00:00.000Z", kills: 99, deaths: 1, adr: 200, won: true }), // before the window
    ];
    const daily = matchDaily(list, "2026-10-29", "2026-10-31", "Europe/Moscow");
    // K/D per match is kills / max(1, deaths): 2, 0.5, 15 -> mean 5.8333...
    expect(Object.keys(daily.kd)).toEqual(["2026-10-30", "2026-10-31"]);
    expect(daily.kd["2026-10-30"]).toBeCloseTo((2 + 0.5 + 15) / 3, 9);
    expect(daily.kd["2026-10-31"]).toBe(1);
    expect(daily.adr["2026-10-30"]).toBeCloseTo(80, 9);
    expect(daily.adr["2026-10-31"]).toBe(70);
    expect(daily.winRate["2026-10-30"]).toBeCloseTo((100 + 0 + 100) / 3, 9);
    expect(daily.winRate["2026-10-31"]).toBe(0);
    // a day without matches has no key (a gap is not a zero)
    expect(daily.kd).not.toHaveProperty("2026-10-29");
  });

  it("emptyListGivesEmptySeries", () => {
    expect(matchDaily([], "2026-10-01", "2026-10-31", "UTC")).toEqual({ kd: {}, adr: {}, winRate: {} });
  });

  it("daysFollowTheStaffZone", () => {
    const list = [match({ id: "z", finishedAt: "2026-10-30T22:30:00.000Z" })];
    expect(Object.keys(matchDaily(list, "2026-10-30", "2026-10-31", "UTC").kd)).toEqual(["2026-10-30"]);
    expect(Object.keys(matchDaily(list, "2026-10-30", "2026-10-31", "Europe/Moscow").kd)).toEqual(["2026-10-31"]);
  });
});

// ---------------------------------------------------------------------------------------------- loadMatches

const deps = testDeps();
let userId = "";
let counter = 0;

beforeEach(async () => {
  await resetDb(deps.sql);
  delete process.env.FACEIT_API_KEY;
  const now = deps.now();
  counter += 1;
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`f${counter}@faceit.gg`}, 'Nova', 'x', 'teamPlayer', ${now}, ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  userId = row.id;
});
afterEach(() => {
  delete process.env.FACEIT_API_KEY;
});
afterAll(() => deps.sql.end());

const KEY = "test-key-do-not-leak";
const PLAYER_ID = "11111111-2222-4333-8444-555555555555";
const STATS_URL = `https://open.faceit.com/data/v4/players/${PLAYER_ID}/games/cs2/stats?limit=100`;

interface Call {
  url: string;
  init: RequestInit | undefined;
}

/** A fake `fetch`: answers by URL from `routes`, a thrown value or a Response; records every call. */
function fakeHttp(routes: Record<string, () => Response | Promise<Response>>): { http: typeof fetch; calls: Call[] } {
  const calls: Call[] = [];
  const http = (async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    const route = routes[url];
    if (!route) throw new Error(`unexpected request ${url}`);
    return route();
  }) as typeof fetch;
  return { http, calls };
}

const json = (body: unknown, status = 200): Response => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const statsBody = (): unknown => ({ items: [CORE_SAMPLE, { stats: { Map: "de_nuke", Result: "0" } }, { stats: { Result: "0", Score: "13 / 7", Kills: "10", Deaths: "18", "Match Finished At": 1759900000 } }] });

describe("loadMatches", () => {
  it("missingKeyIsNoKey", async () => {
    const { http, calls } = fakeHttp({});
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http)).toEqual({ status: "no-key" });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, "")).toEqual({ status: "no-key" });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, "  \t\n ")).toEqual({ status: "no-key" });
    process.env.FACEIT_API_KEY = "   ";
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http)).toEqual({ status: "no-key" });
    expect(calls).toHaveLength(0);
  });

  it("theKeyIsTrimmedBeforeItIsSent", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, `  ${KEY}\n`);
    expect(new Headers(calls[0].init?.headers).get("authorization")).toBe(`Bearer ${KEY}`);
  });

  it("neverFollowsRedirects", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(calls[0].init?.redirect).toBe("error");
  });

  it("readsTheKeyFromTheEnvironmentWhenCalled", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    process.env.FACEIT_API_KEY = KEY; // set after the module was imported: read lazily
    const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http);
    expect(r.status).toBe("ok");
    expect(new Headers(calls[0].init?.headers).get("authorization")).toBe(`Bearer ${KEY}`);
  });

  it("requestCarriesBearerKeyAcceptAndATimeout", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(calls).toHaveLength(1);
    const headers = new Headers(calls[0].init?.headers);
    expect(headers.get("authorization")).toBe(`Bearer ${KEY}`);
    expect(headers.get("accept")).toBe("application/json");
    expect(calls[0].init?.signal).toBeInstanceOf(AbortSignal);
  });

  it("noPlayerWithoutIdOrNickname", async () => {
    const { http, calls } = fakeHttp({});
    expect(await loadMatches(deps, userId, {}, http, KEY)).toEqual({ status: "no-player" });
    expect(await loadMatches(deps, userId, { playerId: "  ", nickname: "" }, http, KEY)).toEqual({ status: "no-player" });
    expect(calls).toHaveLength(0);
  });

  it("returnsMappedMatchesAndSkipsUnreadableItems", async () => {
    const { http } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    if (r.status !== "ok") throw new Error(`status ${r.status}`);
    expect(r.stale).toBe(false);
    expect(r.fetchedAt).toEqual(deps.now());
    expect(r.matches.map((m) => m.kills)).toEqual([24, 10]);
    expect(r.matches[0].id).toBe("6f1b6a2e-1c2d-4e5f-8a9b-0c1d2e3f4a5b");
  });

  it("cacheServedWithinTtl", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    const first = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    deps.advance(FACEIT_CACHE_TTL_MS - 1);
    const second = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(calls).toHaveLength(1);
    if (first.status !== "ok" || second.status !== "ok") throw new Error("expected ok");
    expect(second.matches).toEqual(first.matches);
    expect(second.stale).toBe(false);
    expect(second.fetchedAt).toEqual(first.fetchedAt);
  });

  it("ttlIsFifteenMinutesAndAnExpiredCacheIsRefetched", async () => {
    expect(FACEIT_CACHE_TTL_MS).toBe(15 * 60 * 1000);
    let body: unknown = statsBody();
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(body) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    deps.advance(FACEIT_CACHE_TTL_MS);
    body = { items: [{ stats: { Kills: "30", Deaths: "5", Result: "1", Score: "13 / 1", "Match Finished At": 1760100000 } }] };
    const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(calls).toHaveLength(2);
    if (r.status !== "ok") throw new Error("expected ok");
    expect(r.matches.map((m) => m.kills)).toEqual([30]);
    const rows = await deps.sql<{ matches: FaceitMatch[] }[]>`select matches from faceit_cache where user_id = ${userId}`;
    expect(rows).toHaveLength(1);
    expect(rows[0].matches.map((m) => m.kills)).toEqual([30]);
  });

  it("staleCacheWhenFaceitFails", async () => {
    let fail: () => Response | Promise<Response> = () => json(statsBody());
    const { http, calls } = fakeHttp({ [STATS_URL]: () => fail() });
    const first = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    if (first.status !== "ok") throw new Error("expected ok");
    deps.advance(FACEIT_CACHE_TTL_MS + 1);
    for (const broken of [
      () => json({}, 500),
      () => json({}, 401),
      () => json({}, 404),
      () => json({}, 429),
      () => { throw new TypeError("fetch failed"); },
      () => new Response("not json", { status: 200 }),
    ]) {
      fail = broken;
      deps.advance(FAILURE_MEMORY_MS); // each broken answer must really be asked for, not remembered
      const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
      expect(r.status).toBe("ok");
      if (r.status !== "ok") throw new Error("expected ok");
      expect(r.stale).toBe(true);
      expect(r.fetchedAt).toEqual(first.fetchedAt);
      expect(r.matches).toEqual(first.matches);
    }
    expect(calls).toHaveLength(7); // the first fetch + one per broken answer
  });

  it("firstFailureMapsToStatus", async () => {
    const cases: [() => Response | Promise<Response>, string][] = [
      [() => json({}, 401), "unavailable"],
      [() => json({}, 403), "unavailable"],
      [() => json({}, 404), "not-found"],
      [() => json({}, 429), "rate-limited"],
      [() => json({}, 500), "unavailable"],
      [() => json({}, 503), "unavailable"],
      [() => { throw new TypeError("fetch failed"); }, "unavailable"],
      [() => new Response("<html>", { status: 200 }), "unavailable"],
      [() => json({ items: "nope" }), "unavailable"],
      [() => json([]), "unavailable"],
    ];
    for (const [route, status] of cases) {
      deps.advance(FAILURE_MEMORY_MS); // the previous case's failure is no longer remembered
      const { http } = fakeHttp({ [STATS_URL]: route });
      expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status });
    }
    // no matches were cached by the failures: the row only remembers that Faceit failed
    const rows = await deps.sql<{ matches: unknown; fetched_at: Date | null }[]>`
      select matches, fetched_at from faceit_cache where user_id = ${userId}`;
    expect(rows).toEqual([{ matches: null, fetched_at: null }]);
  });

  it("requestTimeoutIsThreeSeconds", async () => {
    expect(REQUEST_TIMEOUT_MS).toBe(3_000);
    const timeout = vi.spyOn(AbortSignal, "timeout");
    try {
      const { http } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
      await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
      expect(timeout).toHaveBeenCalledWith(3_000);
    } finally {
      timeout.mockRestore();
    }
  });

  it("status429IsRateLimited", async () => {
    const { http } = fakeHttp({ [STATS_URL]: () => json({ message: "slow down" }, 429) });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "rate-limited" });
  });

  it("globalLimiterServesStaleOrSaysRateLimited", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    for (let i = 0; i < 60; i += 1) await rateLimit(deps, "faceit:fetch", 60, 60_000);
    // limiter exhausted and nothing cached: no request is made
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "rate-limited" });
    expect(calls).toHaveLength(0);
    // with a cached row (older than the TTL) the stale copy is served, still without a request
    deps.advance(120_000); // a new limiter window
    const fresh = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(fresh.status).toBe("ok");
    expect(calls).toHaveLength(1);
    deps.advance(FACEIT_CACHE_TTL_MS + 1);
    for (let i = 0; i < 60; i += 1) await rateLimit(deps, "faceit:fetch", 60, 60_000);
    const stale = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(stale.status).toBe("ok");
    if (stale.status !== "ok" || fresh.status !== "ok") throw new Error("expected ok");
    expect(stale.stale).toBe(true);
    expect(stale.matches).toEqual(fresh.matches);
    expect(calls).toHaveLength(1);
  });

  it("cacheHitDoesNotSpendTheGlobalLimit", async () => {
    const { http } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    const [row] = await deps.sql<{ count: number }[]>`select count from rate_limits where key = 'faceit:fetch'`;
    expect(row.count).toBe(1);
  });

  it("nicknameFallbackResolvesPlayer", async () => {
    const lookup = "https://open.faceit.com/data/v4/players?nickname=Nova%20X&game=cs2";
    const { http, calls } = fakeHttp({
      [lookup]: () => json({ player_id: PLAYER_ID, nickname: "Nova X" }),
      [STATS_URL]: () => json(statsBody()),
    });
    const r = await loadMatches(deps, userId, { nickname: "Nova X" }, http, KEY);
    expect(r.status).toBe("ok");
    expect(calls.map((c) => c.url)).toEqual([lookup, STATS_URL]);
    for (const call of calls) expect(new Headers(call.init?.headers).get("authorization")).toBe(`Bearer ${KEY}`);
  });

  it("playerIdWinsOverNickname", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID, nickname: "Nova" }, http, KEY);
    expect(calls.map((c) => c.url)).toEqual([STATS_URL]);
  });

  it("unknownNicknameIsNotFound", async () => {
    const lookup = "https://open.faceit.com/data/v4/players?nickname=ghost&game=cs2";
    const { http } = fakeHttp({ [lookup]: () => json({ errors: [] }, 404) });
    expect(await loadMatches(deps, userId, { nickname: "ghost" }, http, KEY)).toEqual({ status: "not-found" });
    deps.advance(FAILURE_MEMORY_MS); // the not-found above is remembered for two minutes
    const { http: noId } = fakeHttp({ [lookup]: () => json({ nickname: "ghost" }) });
    expect(await loadMatches(deps, userId, { nickname: "ghost" }, noId, KEY)).toEqual({ status: "unavailable" });
  });

  it("anUnsafeSyncedPlayerIdIsNeverPutIntoThePath", async () => {
    const lookup = "https://open.faceit.com/data/v4/players?nickname=Nova&game=cs2";
    const { http, calls } = fakeHttp({
      [lookup]: () => json({ player_id: PLAYER_ID }),
      [STATS_URL]: () => json(statsBody()),
    });
    await loadMatches(deps, userId, { playerId: "../../account?x=1", nickname: "Nova" }, http, KEY);
    expect(calls.map((c) => c.url)).toEqual([lookup, STATS_URL]);
    const { http: nothing, calls: none } = fakeHttp({});
    expect(await loadMatches(deps, userId, { playerId: "a/b" }, nothing, KEY)).toEqual({ status: "no-player" });
    expect(none).toHaveLength(0);
  });

  it("theKeyIsNeverLoggedOrReturned", async () => {
    const logged: string[] = [];
    const spies = (["log", "warn", "error", "info", "debug"] as const).map((level) => {
      const original = console[level];
      console[level] = (...args: unknown[]) => void logged.push(args.map(String).join(" "));
      return () => {
        console[level] = original;
      };
    });
    try {
      const { http } = fakeHttp({ [STATS_URL]: () => { throw new Error(`boom with ${KEY}`); } });
      const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
      expect(r).toEqual({ status: "unavailable" });
      expect(JSON.stringify(r)).not.toContain(KEY);
    } finally {
      spies.forEach((restore) => restore());
    }
    expect(logged.join("\n")).not.toContain(KEY);
  });

  it("cacheIsPerPlayerUser", async () => {
    const now = deps.now();
    const [other] = await deps.sql<{ id: string }[]>`
      insert into users (email, nickname, password_hash, role, email_verified_at,
        accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
      values ('other@faceit.gg', 'Other', 'x', 'teamPlayer', ${now}, ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
      returning id`;
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    await loadMatches(deps, other.id, { playerId: PLAYER_ID }, http, KEY);
    expect(calls).toHaveLength(2);
    // deleting the user deletes the cached row
    await deps.sql`delete from users where id = ${userId}`;
    expect(await deps.sql`select 1 from faceit_cache where user_id = ${userId}`).toHaveLength(0);
    expect(await deps.sql`select 1 from faceit_cache where user_id = ${other.id}`).toHaveLength(1);
  });

  it("keepsAtMostOneHundredNewestMatches", async () => {
    const items = Array.from({ length: 130 }, (_, i) => ({ stats: { Kills: String(i + 1), Deaths: "5", "Match Finished At": 1760000000 - i } }));
    const { http } = fakeHttp({ [STATS_URL]: () => json({ items }) });
    const r = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    if (r.status !== "ok") throw new Error("expected ok");
    expect(r.matches).toHaveLength(100);
    expect(r.matches[0].kills).toBe(1);
  });
});

describe("failure memory", () => {
  it("aFailedFetchIsRememberedForTwoMinutes", async () => {
    expect(FAILURE_MEMORY_MS).toBe(120_000);
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json({}, 500) });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls).toHaveLength(1);
    deps.advance(FAILURE_MEMORY_MS - 1);
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls).toHaveLength(1); // remembered: Faceit was not asked again
    deps.advance(1);
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls).toHaveLength(2); // two minutes passed: it retries
  });

  it("theRememberedFailureKeepsItsMappedStatus", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json({}, 404) });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "not-found" });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "not-found" });
    expect(calls).toHaveLength(1);
  });

  it("aFailedNicknameLookupIsRememberedToo", async () => {
    const lookup = "https://open.faceit.com/data/v4/players?nickname=ghost&game=cs2";
    const { http, calls } = fakeHttp({ [lookup]: () => { throw new TypeError("fetch failed"); } });
    expect(await loadMatches(deps, userId, { nickname: "ghost" }, http, KEY)).toEqual({ status: "unavailable" });
    expect(await loadMatches(deps, userId, { nickname: "ghost" }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls).toHaveLength(1);
  });

  it("aSuccessAfterTheMemoryClearsTheFailure", async () => {
    let answer: () => Response = () => json({}, 500);
    const { http, calls } = fakeHttp({ [STATS_URL]: () => answer() });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    deps.advance(FAILURE_MEMORY_MS);
    answer = () => json(statsBody());
    expect((await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).status).toBe("ok");
    const [row] = await deps.sql<{ failed_at: Date | null; failure: string | null }[]>`select failed_at, failure from faceit_cache where user_id = ${userId}`;
    expect(row).toEqual({ failed_at: null, failure: null });
    expect(calls).toHaveLength(2);
  });

  it("staleMatchesStillWinOverARecentFailure", async () => {
    let answer: () => Response = () => json(statsBody());
    const { http, calls } = fakeHttp({ [STATS_URL]: () => answer() });
    const first = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    if (first.status !== "ok") throw new Error("expected ok");
    deps.advance(FACEIT_CACHE_TTL_MS + 1);
    answer = () => json({}, 500);
    const failed = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(failed).toEqual({ status: "ok", matches: first.matches, fetchedAt: first.fetchedAt, stale: true });
    expect(calls).toHaveLength(2);
    deps.advance(60_000);
    const again = await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(again).toEqual(failed); // still the stale list, with no new request
    expect(calls).toHaveLength(2);
    deps.advance(60_000);
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect(calls).toHaveLength(3); // the memory is over: Faceit is asked again
  });

  it("theOwnRateLimiterIsNotRememberedAsAFailure", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()) });
    for (let i = 0; i < 60; i += 1) await rateLimit(deps, "faceit:fetch", 60, 60_000);
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "rate-limited" });
    deps.advance(60_000); // a new limiter window, well inside the failure memory
    expect((await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).status).toBe("ok");
    expect(calls).toHaveLength(1);
  });
});

describe("cache identity", () => {
  const OTHER_ID = "99999999-8888-4777-8666-555555555555";
  const OTHER_URL = `https://open.faceit.com/data/v4/players/${OTHER_ID}/games/cs2/stats?limit=100`;

  it("theRowRemembersWhoItWasFetchedFor", async () => {
    const lookup = "https://open.faceit.com/data/v4/players?nickname=Nova&game=cs2";
    const { http } = fakeHttp({ [STATS_URL]: () => json(statsBody()), [lookup]: () => json({ player_id: PLAYER_ID }) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    const ref = () => deps.sql<{ faceit_ref: string }[]>`select faceit_ref from faceit_cache where user_id = ${userId}`;
    expect(await ref()).toEqual([{ faceit_ref: PLAYER_ID }]);
    deps.advance(FACEIT_CACHE_TTL_MS);
    await loadMatches(deps, userId, { nickname: "Nova" }, http, KEY);
    expect(await ref()).toEqual([{ faceit_ref: "nick:Nova" }]);
  });

  it("aNewPlayerIdNeverServesTheOldAccountsMatches", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json(statsBody()), [OTHER_URL]: () => json({}, 500) });
    expect((await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).status).toBe("ok");
    // within the TTL, but for another account: a miss, not a hit
    expect(await loadMatches(deps, userId, { playerId: OTHER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls.map((c) => c.url)).toEqual([STATS_URL, OTHER_URL]);
    // and not stale either, after the TTL, while the new fetch keeps failing
    deps.advance(FACEIT_CACHE_TTL_MS + FAILURE_MEMORY_MS);
    expect(await loadMatches(deps, userId, { playerId: OTHER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    const [row] = await deps.sql<{ matches: unknown; faceit_ref: string }[]>`select matches, faceit_ref from faceit_cache where user_id = ${userId}`;
    expect(row).toEqual({ matches: null, faceit_ref: OTHER_ID }); // the old account's list is gone
  });

  it("aNewPlayerIdIsFetchedFreshWhenFaceitWorks", async () => {
    const { http } = fakeHttp({
      [STATS_URL]: () => json(statsBody()),
      [OTHER_URL]: () => json({ items: [{ stats: { Kills: "30", Deaths: "5", Result: "1", Score: "13 / 1", "Match Finished At": 1760100000 } }] }),
    });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    const r = await loadMatches(deps, userId, { playerId: OTHER_ID }, http, KEY);
    if (r.status !== "ok") throw new Error("expected ok");
    expect(r.matches.map((m) => m.kills)).toEqual([30]);
  });

  it("aRowWithoutARefIsAMiss", async () => {
    const mapped = [mapMatch(CORE_SAMPLE, FIXTURE_NOW)];
    await deps.sql`insert into faceit_cache (user_id, matches, fetched_at) values (${userId}, ${deps.sql.json(mapped as never)}, ${deps.now()})`;
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json({}, 500) });
    expect(await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY)).toEqual({ status: "unavailable" });
    expect(calls).toHaveLength(1);
  });

  it("aRememberedFailureForAnotherAccountDoesNotBlockTheNewOne", async () => {
    const { http, calls } = fakeHttp({ [STATS_URL]: () => json({}, 500), [OTHER_URL]: () => json(statsBody()) });
    await loadMatches(deps, userId, { playerId: PLAYER_ID }, http, KEY);
    expect((await loadMatches(deps, userId, { playerId: OTHER_ID }, http, KEY)).status).toBe("ok");
    expect(calls).toHaveLength(2);
  });
});

describe("raceDeadline", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("deadlineIsFiveSeconds", () => {
    expect(FACEIT_PAGE_DEADLINE_MS).toBe(5_000);
  });

  it("answersWithTheFallbackWhenWorkIsSlow", async () => {
    vi.useFakeTimers();
    const slow = new Promise<string>((resolve) => setTimeout(() => resolve("late"), 10_000));
    const raced = raceDeadline(slow, 5_000, "fallback");
    await vi.advanceTimersByTimeAsync(5_000);
    expect(await raced).toBe("fallback");
    await vi.advanceTimersByTimeAsync(10_000); // the late result is ignored, nothing throws
  });

  it("answersWithTheResultWhenWorkIsFast", async () => {
    vi.useFakeTimers();
    const raced = raceDeadline(new Promise<string>((resolve) => setTimeout(() => resolve("done"), 100)), 5_000, "fallback");
    await vi.advanceTimersByTimeAsync(100);
    expect(await raced).toBe("done");
    expect(vi.getTimerCount()).toBe(0); // the deadline timer is cleared
  });

  it("aRejectionBeforeTheDeadlineIsPassedOn", async () => {
    await expect(raceDeadline(Promise.reject(new Error("db down")), 5_000, "fallback")).rejects.toThrow("db down");
  });

  it("aRejectionAfterTheDeadlineIsSwallowedNotUnhandled", async () => {
    vi.useFakeTimers();
    const unhandled: unknown[] = [];
    const listener = (reason: unknown): void => void unhandled.push(reason);
    process.on("unhandledRejection", listener);
    try {
      const late = new Promise<string>((_, reject) => setTimeout(() => reject(new Error("late failure")), 10_000));
      const raced = raceDeadline(late, 5_000, "fallback");
      await vi.advanceTimersByTimeAsync(5_000);
      expect(await raced).toBe("fallback");
      await vi.advanceTimersByTimeAsync(10_000);
      vi.useRealTimers();
      await new Promise((resolve) => setImmediate(resolve));
      expect(unhandled).toEqual([]);
    } finally {
      process.off("unhandledRejection", listener);
    }
  });
});

// ---------------------------------------------------------------------------------------------- helpers

function match(over: Partial<FaceitMatch> & { id: string }): FaceitMatch {
  return {
    map: "de_mirage",
    won: true,
    scoreOwn: 13,
    scoreOther: 10,
    kills: 20,
    deaths: 10,
    assists: 3,
    adr: 80,
    rounds: 23,
    finishedAt: "2026-10-30T12:00:00.000Z",
    ...over,
  };
}
