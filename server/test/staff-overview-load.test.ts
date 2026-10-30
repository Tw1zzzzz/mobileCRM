import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { requireStaff, type StaffContext } from "../lib/staff/access";
import { DEFAULT_SHOW, loadOverview, parseFilters } from "../lib/staff/overview";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

type Role = "soloPlayer" | "teamPlayer" | "staff";

let counter = 0;
async function addUser(nickname: string, role: Role, teamId: string | null): Promise<string> {
  counter += 1;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`o${counter}@overview.gg`}, ${nickname}, 'x', ${role}, ${teamId}, ${now},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return row.id;
}

async function makeTeam(name: string, inviteCode: string): Promise<{ teamId: string; staffId: string }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values (${name}, ${inviteCode}, ${"S" + inviteCode.slice(1)}, ${deps.now()})
    returning id`;
  const staffId = await addUser(`${name}-staff`, "staff", t.id);
  await deps.sql`update teams set owner_id = ${staffId} where id = ${t.id}`;
  return { teamId: t.id, staffId };
}

async function putDoc(userId: string, collection: string, document: unknown): Promise<void> {
  await deps.sql`
    insert into sync_documents (user_id, collection, version, document, updated_at)
    values (${userId}, ${collection}, 1, ${deps.sql.json(document as never)}, ${deps.now()})
    on conflict (user_id, collection) do update set document = excluded.document`;
}

const ctxOf = (userId: string): Promise<StaffContext> => requireStaff(deps, userId);

describe("parseFilters", () => {
  const ids = ["p1", "p2"];

  it("defaultsToWholeTeamAndTheDefaultMetrics", () => {
    expect(parseFilters({}, ids)).toEqual({ who: "team", show: DEFAULT_SHOW });
    expect(DEFAULT_SHOW).toEqual(["state", "sleep"]);
  });

  it("acceptsAPlayerOfTheRoster", () => {
    expect(parseFilters({ who: "p2" }, ids).who).toBe("p2");
    expect(parseFilters({ who: "team" }, ids).who).toBe("team");
  });

  it("unknownWhoFallsBackToTheTeam", () => {
    for (const who of ["nobody", "", "P1", "p1 ", "__proto__", "../p1", ["x", "p1"]]) {
      expect(parseFilters({ who }, ids).who).toBe("team");
    }
  });

  it("parsesShowInCanonicalOrderWithoutDuplicates", () => {
    expect(parseFilters({ show: "food,state,food" }, ids).show).toEqual(["state", "food"]);
    expect(parseFilters({ show: "sleep" }, ids).show).toEqual(["sleep"]);
  });

  it("emptyShowMeansNothingSelected", () => {
    expect(parseFilters({ show: "" }, ids).show).toEqual([]);
  });

  it("gameAndGarbageAreIgnored", () => {
    expect(parseFilters({ show: "game,state" }, ids).show).toEqual(["state"]);
    expect(parseFilters({ show: "game" }, ids).show).toEqual(DEFAULT_SHOW);
    expect(parseFilters({ show: "nonsense" }, ids).show).toEqual(DEFAULT_SHOW);
  });
});

describe("loadOverview", () => {
  it("emptyTeamCarriesThePlayerCode", async () => {
    const { staffId } = await makeTeam("Alpha", "PALPHA");
    const page = await loadOverview(deps, await ctxOf(staffId), {});
    expect(page).toEqual({ kind: "empty", playerCode: "PALPHA" });
  });

  it("buildsTheOverviewFromSyncedDocuments", async () => {
    const { teamId, staffId } = await makeTeam("Alpha", "PALPHA");
    const player = await addUser("vega", "teamPlayer", teamId);
    // deps.now() = 2026-10-26T12:00Z+, which is 2026-10-26 in Moscow
    await putDoc(player, "sleep", {
      sessions: [{ id: "s1", start: "2026-10-25T20:00:00Z", end: "2026-10-26T04:00:00Z" }],
    });
    await putDoc(player, "wellbeing", { mood: [{ id: "m1", day: "2026-10-26T08:00:00Z", mood: 8, energy: 6 }] });
    const page = await loadOverview(deps, await ctxOf(staffId), {});
    if (page.kind !== "data") throw new Error("expected data");
    expect(page.players).toEqual([{ id: player, nickname: "vega" }]);
    expect(page.filters).toEqual({ who: "team", show: DEFAULT_SHOW });
    expect(page.overview.today).toBe("2026-10-26");
    expect(page.overview.sleep.daily.at(-1)).toBe(8);
    expect(page.overview.mood.count).toBe(1);
    expect(page.overview.mood.average).toBe(8);
    // sleep 8 h, mood 8, energy 6, no cognitive: (26.25 + 24 + 15) * 115/85 = 88.3
    expect(page.overview.teamReadiness).toBe(88);
  });

  it("whoOfAnotherTeamsPlayerFallsBackToTheTeam", async () => {
    const mine = await makeTeam("Alpha", "PALPHA");
    const other = await makeTeam("Bravo", "PBRAVO");
    const myPlayer = await addUser("mine", "teamPlayer", mine.teamId);
    const theirPlayer = await addUser("theirs", "teamPlayer", other.teamId);
    await putDoc(myPlayer, "wellbeing", { mood: [{ id: "m1", day: "2026-10-26T08:00:00Z", mood: 5, energy: 5 }] });
    await putDoc(theirPlayer, "wellbeing", { mood: [{ id: "m2", day: "2026-10-26T08:00:00Z", mood: 10, energy: 10 }] });

    const ctx = await ctxOf(mine.staffId);
    const page = await loadOverview(deps, ctx, { who: theirPlayer });
    if (page.kind !== "data") throw new Error("expected data");
    expect(page.filters.who).toBe("team");
    expect(page.players.map((p) => p.id)).toEqual([myPlayer]);
    expect(page.overview.who).toBe("team");
    // nothing of the other team's data reached the numbers
    expect(page.overview.mood.average).toBe(5);
    expect(JSON.stringify(page)).not.toContain(theirPlayer);
    expect(JSON.stringify(page)).not.toContain("theirs");
  });

  it("whoOfOwnPlayerSelectsThemAndSoloPlayersAreNotOffered", async () => {
    const { teamId, staffId } = await makeTeam("Alpha", "PALPHA");
    const a = await addUser("aa", "teamPlayer", teamId);
    const b = await addUser("bb", "teamPlayer", teamId);
    const solo = await addUser("sol", "soloPlayer", null);
    await putDoc(a, "wellbeing", { mood: [{ id: "m1", day: "2026-10-26T08:00:00Z", mood: 4, energy: 4 }] });
    await putDoc(b, "wellbeing", { mood: [{ id: "m2", day: "2026-10-26T08:00:00Z", mood: 8, energy: 8 }] });
    const ctx = await ctxOf(staffId);

    const page = await loadOverview(deps, ctx, { who: b });
    if (page.kind !== "data") throw new Error("expected data");
    expect(page.filters.who).toBe(b);
    expect(page.overview.mood.average).toBe(8);
    // team readiness follows the roster, not the filter: (46 + 92) / 2
    expect(page.overview.teamReadiness).toBe(69);

    const fallback = await loadOverview(deps, ctx, { who: solo });
    if (fallback.kind !== "data") throw new Error("expected data");
    expect(fallback.filters.who).toBe("team");
  });

  it("todayIsTheDayInTheStaffZone", async () => {
    const { teamId, staffId } = await makeTeam("Alpha", "PALPHA");
    await addUser("vega", "teamPlayer", teamId);
    const ctx = await ctxOf(staffId);
    // 12:00Z: Moscow is still 26 Oct, Auckland is already 27 Oct
    const moscow = await loadOverview(deps, ctx, {}, "Europe/Moscow");
    const auckland = await loadOverview(deps, ctx, {}, "Pacific/Auckland");
    if (moscow.kind !== "data" || auckland.kind !== "data") throw new Error("expected data");
    expect(moscow.overview.today).toBe("2026-10-26");
    expect(auckland.overview.today).toBe("2026-10-27");
  });

  it("neverReadsBodyProfile", async () => {
    const { teamId, staffId } = await makeTeam("Alpha", "PALPHA");
    const player = await addUser("vega", "teamPlayer", teamId);
    await putDoc(player, "body-profile", { weightKg: 77 });
    const page = await loadOverview(deps, await ctxOf(staffId), {});
    expect(JSON.stringify(page)).not.toContain("77");
  });
});
