import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { requireStaff } from "../lib/staff/access";
import {
  DEFAULT_METRICS,
  MAX_METRICS,
  loadStatistics,
  parseStatsParams,
  statsHref,
  type StatsPage,
} from "../lib/staff/statistics-page";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";
import {
  FACEIT_IDS, KEY, TZ, daysBefore, fakeFaceit, matchesFor, pearsonRef, seedState, signed, type DayRow, type FakeFaceit,
} from "./staff-statistics-fixture";

const deps = testDeps();
const TODAY = "2026-10-26"; // the fixed test clock is 2026-10-26 15:00 in Moscow
const IDS = ["p1", "p2"];

beforeEach(async () => {
  await resetDb(deps.sql);
  vi.stubEnv("FACEIT_API_KEY", KEY);
});
afterEach(() => {
  vi.unstubAllEnvs();
});
afterAll(() => deps.sql.end());

type Data = Extract<StatsPage, { kind: "data" }>;

async function open(t: CardTeam, query: Record<string, string>, faceit: FakeFaceit | null = null): Promise<Data> {
  const page = await loadStatistics(deps, t.ctx, query, TZ, faceit ? { http: faceit.http, apiKey: KEY } : {});
  if (page.kind !== "data") throw new Error("expected data");
  return page;
}

const SLEEP = [6, 7, 8, 6.5, 7.5, 8.5, 5.5, 9, 7, 6];
const MOOD = [5, 6, 4, 7, 5, 6, 8, 3, 6, 5];
const DAYS = daysBefore(TODAY, 10);

function rowsOf(sleep: number[], mood: number[], extra: Partial<DayRow>[] = []): DayRow[] {
  return DAYS.map((day, i) => ({ day, sleep: sleep[i], mood: mood[i], ...extra[i] }));
}

/** Sleep drives K/D: kills = 2 * sleep over 10 deaths, so K/D = 0.2 * sleep (r = +1). */
function positiveMatches(sleep: number[]): unknown[] {
  return matchesFor(DAYS.map((day, i) => ({ day, kills: 2 * sleep[i] })));
}

async function connect(userId: string, playerId: string | null): Promise<void> {
  await putDoc(deps, userId, "faceit", { nickname: "nick", playerId, snapshots: [] });
}

function cell(page: Data, row: string, column: string): string {
  const matrix = page.view.matrix;
  if (matrix === null) throw new Error("no matrix");
  const r = matrix.rows.findIndex((x) => x.name === row);
  const c = matrix.head.indexOf(column);
  return matrix.rows[r].cells[c].text;
}

describe("parseStatsParams", () => {
  it("statsParamsFallBacks", () => {
    // who: only the team or a roster id; anything else is the team
    expect(parseStatsParams({}, IDS, TODAY).who).toBe("team");
    expect(parseStatsParams({ who: "p2" }, IDS, TODAY).who).toBe("p2");
    expect(parseStatsParams({ who: "someone-else" }, IDS, TODAY).who).toBe("team");
    expect(parseStatsParams({ who: "" }, IDS, TODAY).who).toBe("team");
    expect(parseStatsParams({ who: ["p2", "p1"] }, IDS, TODAY).who).toBe("p2");

    // period: presets, anything else is 30 days
    expect(parseStatsParams({}, IDS, TODAY)).toMatchObject({ period: 30, range: { from: "2026-09-27", to: TODAY } });
    expect(parseStatsParams({ period: "7" }, IDS, TODAY).range.days).toHaveLength(7);
    expect(parseStatsParams({ period: "90" }, IDS, TODAY).range.days).toHaveLength(90);
    expect(parseStatsParams({ period: "365" }, IDS, TODAY)).toMatchObject({ period: 365, range: { from: "2025-10-27" } });
    expect(parseStatsParams({ period: "14" }, IDS, TODAY).period).toBe(30);
    expect(parseStatsParams({ period: "-3" }, IDS, TODAY).period).toBe(30);
    // from/to only count for a custom period
    expect(parseStatsParams({ period: "7", from: "2026-01-01", to: "2026-01-05" }, IDS, TODAY).range.to).toBe(TODAY);

    // a good custom range
    const custom = parseStatsParams({ period: "custom", from: "2026-10-01", to: "2026-10-10" }, IDS, TODAY);
    expect(custom).toMatchObject({ period: "custom", range: { from: "2026-10-01", to: "2026-10-10" } });
    expect(custom.range.days).toHaveLength(10);
    expect(parseStatsParams({ period: "custom", from: "2026-10-05", to: "2026-10-05" }, IDS, TODAY).range.days).toEqual(["2026-10-05"]);
    // a year (366 days) is the longest custom range
    expect(parseStatsParams({ period: "custom", from: "2025-10-01", to: "2026-09-30" }, IDS, TODAY).range.days).toHaveLength(365);
    expect(parseStatsParams({ period: "custom", from: "2025-09-30", to: "2026-09-30" }, IDS, TODAY).range.days).toHaveLength(366);

    // bad custom ranges are the last 30 days
    const bad: Record<string, string>[] = [
      { period: "custom" },
      { period: "custom", from: "2026-10-10" },
      { period: "custom", from: "2026-10-10", to: "2026-10-01" },
      { period: "custom", from: "2025-09-29", to: "2026-09-30" },
      { period: "custom", from: "2026-02-30", to: "2026-03-05" },
      { period: "custom", from: "26-10-01", to: "2026-10-05" },
      { period: "custom", from: "2026-10-01'; drop", to: "2026-10-05" },
    ];
    for (const query of bad) {
      expect(parseStatsParams(query, IDS, TODAY).range, JSON.stringify(query)).toMatchObject({ from: "2026-09-27", to: TODAY });
    }
  });

  it("defaultMetricsAndMaxEight", () => {
    expect(DEFAULT_METRICS).toEqual(["mood", "sleep", "screen", "elo", "kd", "adr"]);
    expect(MAX_METRICS).toBe(8);
    expect(parseStatsParams({}, IDS, TODAY).metrics).toEqual(DEFAULT_METRICS);
    expect(parseStatsParams({ m: "kd,sleep" }, IDS, TODAY).metrics).toEqual(["kd", "sleep"]);
    // unknown ids (and the dropped KAST) are skipped; duplicates count once
    expect(parseStatsParams({ m: "sleep,kast,nope,sleep,adr" }, IDS, TODAY).metrics).toEqual(["sleep", "adr"]);
    // nothing known -> the default; an empty value is «nothing selected»
    expect(parseStatsParams({ m: "kast,zzz" }, IDS, TODAY).metrics).toEqual(DEFAULT_METRICS);
    expect(parseStatsParams({ m: "" }, IDS, TODAY).metrics).toEqual([]);
    // more than eight are cut to the first eight
    const all = "mood,energy,sleep,screen,cog,balance,kcal,elo,kd,adr,wr";
    expect(parseStatsParams({ m: all }, IDS, TODAY).metrics).toEqual(all.split(",").slice(0, 8));
  });

  it("hrefLeavesOutDefaultsAndRoundTrips", () => {
    const base = parseStatsParams({}, IDS, TODAY);
    expect(statsHref(base)).toBe("/staff/statistics");
    expect(statsHref(base, { who: "p1" })).toBe("/staff/statistics?who=p1");
    expect(statsHref(base, { period: 90 })).toBe("/staff/statistics?period=90");
    expect(statsHref(base, { metrics: ["sleep", "kd"] })).toBe("/staff/statistics?m=sleep,kd");
    expect(statsHref(base, { metrics: [] })).toBe("/staff/statistics?m=");
    const custom = parseStatsParams({ period: "custom", from: "2026-10-01", to: "2026-10-10", who: "p2", m: "kd,wr" }, IDS, TODAY);
    const href = statsHref(custom);
    expect(href).toBe("/staff/statistics?who=p2&m=kd,wr&period=custom&from=2026-10-01&to=2026-10-10");
    const query = Object.fromEntries(new URL(href, "http://x").searchParams);
    expect(parseStatsParams(query, IDS, TODAY)).toEqual(custom);
  });
});

describe("loadStatistics", () => {
  it("keyLinksFromSyncedAndFaceitData", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD), { playerId: FACEIT_IDS.vega });
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: positiveMatches(SLEEP) });
    const page = await open(t, { who: t.ids.vega, m: "sleep,kd,mood" }, faceit);

    const kd = SLEEP.map((s) => (2 * s) / 10);
    const moodKd = pearsonRef(MOOD, kd);
    expect(page.view.links.map((l) => l.title)).toEqual(["Сон ↔ K/D", "Настроение ↔ K/D"]);
    expect(page.view.links[0]).toMatchObject({ r: "+1.00", strength: "Сильная прямая связь" });
    expect(page.view.links[0].example).toBe("Когда сон выше обычного (от 7.0 ч), K/D в среднем 1.57; когда ниже — 1.20.");
    expect(page.view.links[1].r).toBe(signed(moodKd));

    // the matrix agrees, in both triangles, and the diagonal is a dash
    expect(page.view.matrix?.head).toEqual(["Сон", "K/D", "Настроение"]);
    expect(cell(page, "Сон", "K/D")).toBe("+1.00");
    expect(cell(page, "K/D", "Сон")).toBe("+1.00");
    expect(cell(page, "Сон", "Сон")).toBe("—");
    expect(cell(page, "Настроение", "Сон")).toBe(signed(pearsonRef(MOOD, SLEEP)));
    expect(faceit.calls).toEqual([`https://open.faceit.com/data/v4/players/${FACEIT_IDS.vega}/games/cs2/stats?limit=100`]);
  });

  it("teamModePoolsPlayers", async () => {
    const t = await seedCardTeam(deps);
    const sleepNova = [5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 9.5];
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD), { playerId: FACEIT_IDS.vega });
    await seedState(deps, t.ids.nova, rowsOf(sleepNova, MOOD), { playerId: FACEIT_IDS.nova });
    // vega: K/D rises with sleep; nova: K/D falls with sleep (kills = 30 - 2 * sleep)
    const novaMatches = matchesFor(DAYS.map((day, i) => ({ day, kills: 30 - 2 * sleepNova[i] })));
    const faceit = () => fakeFaceit({ [FACEIT_IDS.vega]: positiveMatches(SLEEP), [FACEIT_IDS.nova]: novaMatches });

    const team = await open(t, { m: "sleep,kd" }, faceit());
    const pooledSleep = [...SLEEP, ...sleepNova];
    const pooledKd = [...SLEEP.map((s) => (2 * s) / 10), ...sleepNova.map((s) => (30 - 2 * s) / 10)];
    expect(cell(team, "Сон", "K/D")).toBe(signed(pearsonRef(pooledSleep, pooledKd)));
    expect(cell(team, "Сон", "K/D")).not.toMatch(/1\.00/);

    // the players on their own are the two opposite directions
    await deps.sql`delete from faceit_cache`;
    const vega = await open(t, { who: t.ids.vega, m: "sleep,kd" }, faceit());
    await deps.sql`delete from faceit_cache`;
    const nova = await open(t, { who: t.ids.nova, m: "sleep,kd" }, faceit());
    expect(cell(vega, "Сон", "K/D")).toBe("+1.00");
    expect(cell(nova, "Сон", "K/D")).toBe("−1.00");
  });

  it("playerModeIgnoresOthers", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD));
    await seedState(deps, t.ids.nova, rowsOf(SLEEP.map((s) => 14 - s), MOOD));
    await seedState(deps, t.ids.outsider, rowsOf(SLEEP, MOOD));

    const team = await open(t, { m: "sleep,mood" });
    const vega = await open(t, { who: t.ids.vega, m: "sleep,mood" });
    expect(vega.params.who).toBe(t.ids.vega);
    expect(cell(vega, "Сон", "Настроение")).toBe(signed(pearsonRef(SLEEP, MOOD)));
    expect(cell(team, "Сон", "Настроение")).not.toBe(cell(vega, "Сон", "Настроение"));

    // another team's player, a solo player, staff and garbage all mean the whole team
    for (const who of [t.ids.outsider, t.ids.solo, t.ids.staff, t.ids.otherStaff, "nonsense", "' or 1=1 --"]) {
      const page = await open(t, { who, m: "sleep,mood" });
      expect(page.params.who, who).toBe("team");
      expect(cell(page, "Сон", "Настроение")).toBe(cell(team, "Сон", "Настроение"));
      expect(page.players.map((p) => p.id)).not.toContain(who);
    }
  });

  it("faceitDownStillShowsStateMetrics", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD, DAYS.map((_, i) => ({ elo: 2000 + 10 * i }))), { playerId: FACEIT_IDS.vega });
    const query = { who: t.ids.vega, m: "sleep,mood,elo,kd" };

    // no key on the server: nothing is called, state pairs and ELO (synced snapshots) still work
    vi.stubEnv("FACEIT_API_KEY", "");
    const calls = fakeFaceit({});
    const noKey = await loadStatistics(deps, t.ctx, query, TZ, { http: calls.http, apiKey: "" });
    if (noKey.kind !== "data") throw new Error("expected data");
    expect(calls.calls).toEqual([]);
    expect(noKey.view.notices.map((n) => n.message)).toEqual(["Faceit не подключён к серверу"]);
    expect(cell(noKey, "Сон", "Настроение")).toBe(signed(pearsonRef(SLEEP, MOOD)));
    expect(cell(noKey, "Сон", "K/D")).toBe("мало данных");
    expect(noKey.view.multiples.find((m) => m.id === "kd")).toMatchObject({ hasData: false });
    const elo = noKey.view.multiples.find((m) => m.id === "elo");
    expect(elo).toMatchObject({ hasData: true, min: "2000", max: "2090", avg: "2045" });
    expect(noKey.view.hasAnyData).toBe(true);

    // Faceit answers 500 with a cold cache
    const down = await open(t, query, fakeFaceit({ [FACEIT_IDS.vega]: 500 }));
    expect(down.view.notices.map((n) => n.message)).toEqual(["Faceit временно недоступен — попробуйте позже"]);
    expect(cell(down, "Сон", "Настроение")).toBe(signed(pearsonRef(SLEEP, MOOD)));
    expect(cell(down, "Сон", "K/D")).toBe("мало данных");
    expect(down.view.game?.tiles.map((x) => x.value)).toEqual(["нет данных", "нет данных", "нет данных", "нет данных", "2090"]);
  });

  it("aColdCacheFailureForOneOfTwoPlayersStillRenders", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD), { playerId: FACEIT_IDS.vega });
    await seedState(deps, t.ids.nova, rowsOf(SLEEP, MOOD), { playerId: FACEIT_IDS.nova });
    // ghost never connected Faceit; nova's request fails
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: positiveMatches(SLEEP), [FACEIT_IDS.nova]: 500 });
    const page = await open(t, { m: "sleep,kd" }, faceit);
    // only vega's matches are used: r is vega's own +1.00
    expect(cell(page, "Сон", "K/D")).toBe("+1.00");
    expect(page.view.notices.map((n) => n.message)).toEqual(["Данных Faceit нет у 2 из 3 игроков"]);
    expect(page.view.game?.tiles[0]).toEqual({ label: "Сыграно карт", value: "10" });
  });

  it("onlyAsksFaceitForGameMetrics", async () => {
    const t = await seedCardTeam(deps);
    await seedState(deps, t.ids.vega, rowsOf(SLEEP, MOOD), { playerId: FACEIT_IDS.vega });
    const faceit = fakeFaceit({ [FACEIT_IDS.vega]: positiveMatches(SLEEP) });
    const none = await open(t, { m: "sleep,mood,screen,cog" }, faceit);
    expect(faceit.calls).toEqual([]);
    expect(none.view.game).toBeNull();
    expect(none.view.notices).toEqual([]);
    await open(t, { m: "sleep,wr" }, faceit);
    expect(faceit.calls.length).toBe(1);
  });

  it("asksOnlyTheFirstTwelvePlayersWithALimitOnParallelCalls", async () => {
    const t = await seedCardTeam(deps);
    const answers: Record<string, unknown[]> = {};
    const roster = await deps.sql<{ id: string }[]>`select id from users where team_id = ${t.ids.team} and role = 'teamPlayer'`;
    for (let i = 0; roster.length < 14; i++) {
      const [row] = await deps.sql<{ id: string }[]>`
        insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
          accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
        values (${`extra${i}@stats.gg`}, ${`extra${i}`}, 'x', 'teamPlayer', ${t.ids.team}, ${deps.now()},
          ${deps.now()}, ${deps.now()}, '2026-10-26', ${deps.now()}, ${deps.now()}, ${deps.now()})
        returning id`;
      roster.push(row);
      deps.advance(1_000);
    }
    for (const [i, row] of roster.entries()) {
      const playerId = `bbbbbbbb-0000-4000-8000-${String(i).padStart(12, "0")}`;
      await seedState(deps, row.id, rowsOf(SLEEP, MOOD), { playerId });
      answers[playerId] = positiveMatches(SLEEP);
    }
    const faceit = fakeFaceit(answers, 15);
    const page = await open(t, { m: "sleep,kd" }, faceit);
    expect(roster).toHaveLength(14);
    expect(faceit.calls).toHaveLength(12);
    expect(new Set(faceit.calls).size).toBe(12);
    expect(faceit.maxInFlight()).toBeLessThanOrEqual(4);
    expect(faceit.maxInFlight()).toBeGreaterThan(1);
    expect(page.view.truncated).toEqual({ shown: 12, total: 14 });
    // the chips still list everybody
    expect(page.players).toHaveLength(14);
    expect(cell(page, "Сон", "K/D")).toBe("+1.00");
  });

  it("gameCardUsesTheMatchesOfThePeriod", async () => {
    const t = await seedCardTeam(deps);
    const rows = DAYS.map((day, i) => ({ day, ...(i === 9 ? { elo: 2100 } : i === 8 ? { elo: 2050 } : {}) }));
    await seedState(deps, t.ids.vega, rows.map((r, i) => ({ ...r, sleep: SLEEP[i] })), { playerId: FACEIT_IDS.vega });
    const items = [
      ...matchesFor([
        { day: "2026-10-25", kills: 20, adr: 90, won: true },
        { day: "2026-10-26", kills: 10, adr: 60, won: false },
        { day: "2026-10-26", kills: 15, adr: 75, won: true },
        { day: "2026-07-01", kills: 99, adr: 150, won: true }, // before the 30-day period
      ]),
    ];
    const page = await open(t, { who: t.ids.vega, m: "sleep,kd" }, fakeFaceit({ [FACEIT_IDS.vega]: items }));
    // K/D per match 2.0, 1.0, 1.5 -> mean 1.50; ADR (90 + 60 + 75) / 3 = 75.0; 2 wins of 3 = 67 %
    expect(page.view.game?.tiles).toEqual([
      { label: "Сыграно карт", value: "3" },
      { label: "Средний K/D", value: "1.50" },
      { label: "Средний ADR", value: "75.0" },
      { label: "Win Rate", value: "67%" },
      { label: "ELO на конец периода", value: "2100" },
    ]);
  });

  it("noDataAtAllIsReported", async () => {
    const t = await seedCardTeam(deps);
    const january = { m: "mood,sleep", period: "custom", from: "2026-01-01", to: "2026-01-31" };
    const empty = await open(t, january);
    expect(empty.view.hasAnyData).toBe(false);
    expect(empty.view.links).toEqual([]);
    // data outside the chosen period does not count
    await seedState(deps, t.ids.vega, [{ day: "2026-02-10", sleep: 8, mood: 7 }]);
    expect((await open(t, january)).view.hasAnyData).toBe(false);
    expect((await open(t, { ...january, from: "2026-02-01", to: "2026-02-28" })).view.hasAnyData).toBe(true);
  });

  it("anEmptyTeamHasACodeInsteadOfNumbers", async () => {
    const t = await seedCardTeam(deps);
    const lone = await requireStaff(deps, t.ids.loneStaff);
    const page = await loadStatistics(deps, lone, {}, TZ);
    expect(page.kind).toBe("empty");
    expect(page.kind === "empty" && page.playerCode).toMatch(/^P/);
  });
});
