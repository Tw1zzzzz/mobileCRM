import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { removeMember } from "../lib/team/members";
import { playerDocuments, requireStaff, teamPlayers, type StaffContext } from "../lib/staff/access";
import { handle } from "../lib/http";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

type Role = "soloPlayer" | "teamPlayer" | "staff";

let counter = 0;
async function addUser(
  nickname: string,
  role: Role,
  teamId: string | null,
  opts: { verified?: boolean } = {},
): Promise<string> {
  counter += 1;
  const now = deps.now();
  const verifiedAt = opts.verified === false ? null : now;
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`u${counter}@staff.gg`}, ${nickname}, 'x', ${role}, ${teamId}, ${verifiedAt},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return row.id;
}

/** A team with a staff member; the owner column is set after the staff user exists. */
async function makeTeam(name: string): Promise<{ teamId: string; staffId: string }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values (${name}, ${"P" + name.slice(0, 5).toUpperCase().padEnd(5, "A")}, ${"S" + name.slice(0, 5).toUpperCase().padEnd(5, "A")}, ${deps.now()})
    returning id`;
  const staffId = await addUser(`${name}-staff`, "staff", t.id);
  await deps.sql`update teams set owner_id = ${staffId} where id = ${t.id}`;
  return { teamId: t.id, staffId };
}
async function putDoc(userId: string, collection: string, document: unknown, at: Date = deps.now()): Promise<void> {
  await deps.sql`
    insert into sync_documents (user_id, collection, version, document, updated_at)
    values (${userId}, ${collection}, 1, ${deps.sql.json(document as never)}, ${at})
    on conflict (user_id, collection) do update set document = excluded.document, updated_at = excluded.updated_at`;
}

async function ctxOf(userId: string): Promise<StaffContext> {
  return requireStaff(deps, userId);
}

describe("requireStaff", () => {
  it("returnsTeamContextForStaff", async () => {
    const { teamId, staffId } = await makeTeam("Alpha");
    expect(await requireStaff(deps, staffId)).toEqual({ userId: staffId, teamId, teamName: "Alpha" });
  });

  it("nonStaffRejected", async () => {
    const { teamId } = await makeTeam("Alpha");
    const player = await addUser("pl", "teamPlayer", teamId);
    const solo = await addUser("solo", "soloPlayer", null);
    const unverifiedStaff = await addUser("unv", "staff", teamId, { verified: false });
    for (const id of [player, solo, unverifiedStaff]) {
      await expect(requireStaff(deps, id)).rejects.toMatchObject({ code: "forbidden", status: 403 });
    }
  });

  it("staffWithoutTeamRejected", async () => {
    const lone = await addUser("lone", "staff", null);
    await expect(requireStaff(deps, lone)).rejects.toMatchObject({ code: "forbidden", status: 403 });
  });

  it("unknownUserIsUnauthorized", async () => {
    await expect(requireStaff(deps, "00000000-0000-4000-8000-000000000000")).rejects.toMatchObject({
      code: "unauthorized",
      status: 401,
    });
  });

  it("forbiddenEnvelopeIs403", async () => {
    const solo = await addUser("solo", "soloPlayer", null);
    const res = await handle(async () => {
      await requireStaff(deps, solo);
      return new Response();
    });
    expect(res.status).toBe(403);
    expect((await res.json()).error.code).toBe("forbidden");
  });
});

describe("teamPlayers", () => {
  it("listsOnlyVerifiedTeamPlayersOfOwnTeam", async () => {
    const a = await makeTeam("Alpha");
    const b = await makeTeam("Bravo");
    const p1 = await addUser("p1", "teamPlayer", a.teamId);
    const p2 = await addUser("p2", "teamPlayer", a.teamId);
    await addUser("unverified", "teamPlayer", a.teamId, { verified: false });
    await addUser("other", "teamPlayer", b.teamId);
    await addUser("solo", "soloPlayer", null);
    const players = await teamPlayers(deps, await ctxOf(a.staffId));
    expect(players.map((p) => p.id)).toEqual([p1, p2]);
    expect(players.map((p) => p.nickname)).toEqual(["p1", "p2"]);
  });

  it("lastActivityFromSyncDocuments", async () => {
    const a = await makeTeam("Alpha");
    const quiet = await addUser("quiet", "teamPlayer", a.teamId);
    const busy = await addUser("busy", "teamPlayer", a.teamId);
    const early = new Date("2026-10-20T08:00:00Z");
    const late = new Date("2026-10-25T21:30:00Z");
    await putDoc(busy, "sleep", { sessions: [] }, early);
    await putDoc(busy, "wellbeing", { mood: [] }, late);
    await putDoc(busy, "body-profile", { age: 20 }, new Date("2026-10-22T00:00:00Z"));
    const players = await teamPlayers(deps, await ctxOf(a.staffId));
    const byId = new Map(players.map((p) => [p.id, p]));
    expect(byId.get(quiet)?.lastActivity).toBeNull();
    expect(byId.get(busy)?.lastActivity).toEqual(late);
  });

  it("removedPlayerDisappears", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    const ctx = await ctxOf(a.staffId);
    await putDoc(p, "sleep", { sessions: [] });
    expect((await teamPlayers(deps, ctx)).map((x) => x.id)).toEqual([p]);
    await removeMember(deps, a.teamId, a.staffId, p);
    expect(await teamPlayers(deps, ctx)).toEqual([]);
    await expect(playerDocuments(deps, ctx, p, ["sleep"])).rejects.toMatchObject({ code: "not_found", status: 404 });
  });
});

describe("playerDocuments", () => {
  it("staffCannotReadOtherTeams", async () => {
    const a = await makeTeam("Alpha");
    const b = await makeTeam("Bravo");
    const foreign = await addUser("foreign", "teamPlayer", b.teamId);
    const solo = await addUser("solo", "soloPlayer", null);
    const colleague = a.staffId;
    await putDoc(foreign, "sleep", { sessions: [{ id: "x" }] });
    await putDoc(solo, "sleep", { sessions: [{ id: "y" }] });
    await putDoc(colleague, "sleep", { sessions: [{ id: "z" }] });
    const ctx = await ctxOf(a.staffId);
    for (const id of [foreign, solo, colleague, b.staffId, "00000000-0000-4000-8000-000000000000", "not-a-uuid"]) {
      await expect(playerDocuments(deps, ctx, id, ["sleep"])).rejects.toMatchObject({
        code: "not_found",
        status: 404,
      });
    }
  });

  it("unverifiedPlayerIsNotReadable", async () => {
    const a = await makeTeam("Alpha");
    const unverified = await addUser("unv", "teamPlayer", a.teamId, { verified: false });
    await putDoc(unverified, "sleep", { sessions: [] });
    await expect(playerDocuments(deps, await ctxOf(a.staffId), unverified, ["sleep"])).rejects.toMatchObject({
      code: "not_found",
    });
  });

  it("returnsDocumentsAsStoredAndOmitsMissing", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    const sleep = { sessions: [{ id: "s1", hours: 7.5 }] };
    await putDoc(p, "sleep", sleep);
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["sleep", "wellbeing"]);
    expect(docs).toEqual({ sleep });
    expect("wellbeing" in docs).toBe(false);
  });

  it("bodyProfileNeverReturned", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    await putDoc(p, "body-profile", { age: 19, heightCm: 180, weightKg: 70 });
    await putDoc(p, "sleep", { sessions: [] });
    const ctx = await ctxOf(a.staffId);
    // The type system forbids it; a cast must still not get through.
    await expect(playerDocuments(deps, ctx, p, ["body-profile"] as never)).rejects.toMatchObject({
      code: "forbidden",
      status: 403,
    });
    await expect(playerDocuments(deps, ctx, p, ["sleep", "body-profile"] as never)).rejects.toMatchObject({
      code: "forbidden",
    });
    await expect(playerDocuments(deps, ctx, p, ["nope"] as never)).rejects.toMatchObject({
      code: "validation_failed",
    });
  });

  it("lineupsOnlyTeamVisibility", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    await putDoc(p, "lineups", [
      { id: "1", title: "shared", visibility: "team" },
      { id: "2", title: "diary", visibility: "personal" },
      { id: "3", title: "no visibility field" },
      { id: "4", title: "odd", visibility: "TEAM" },
      "garbage",
      null,
      { id: "5", title: "also shared", visibility: "team" },
    ]);
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["lineups"]);
    expect(docs.lineups).toEqual([
      { id: "1", title: "shared", visibility: "team" },
      { id: "5", title: "also shared", visibility: "team" },
    ]);
  });

  it("lineupsThatIsNotAnArrayYieldsEmpty", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    await putDoc(p, "lineups", { visibility: "team" });
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["lineups"]);
    expect(docs.lineups).toEqual([]);
  });

  it("surveyWithoutAnswers", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    // Every item answered 5: normal items score 100, reversed items score 0 (6 - 5 = 1).
    const answers: Record<string, number> = {};
    for (let i = 1; i <= 24; i++) answers[`q${i}`] = 5;
    const survey = { goals: ["sleep"], role: "awper", level: "semiPro", yearsPlaying: 4, hoursPerWeek: 20 };
    await putDoc(p, "player-survey", {
      survey,
      mindset: [{ id: "m1", date: "2026-10-01T10:00:00Z", answers }],
      onboardingDismissed: true,
    });
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["player-survey"]);
    expect(docs["player-survey"]).toEqual({
      survey,
      mindset: [
        {
          id: "m1",
          date: "2026-10-01T10:00:00Z",
          // 4 questions per trait, 2 normal (5) and 2 reversed (1): mean 3 -> 50.
          scores: { composure: 50, resilience: 50, focus: 50, teamwork: 50, leadership: 50, drive: 50 },
        },
      ],
    });
    expect(JSON.stringify(docs)).not.toContain("answers");
    expect(JSON.stringify(docs)).not.toContain("q1");
  });

  it("surveyScoresMatchTheAppFormula", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    // Strong player: agrees with positive items (5), disagrees with reversed ones (1) -> 100 everywhere.
    const reversed = new Set([7, 8, 9, 10, 11, 12, 19, 20, 21, 22, 23, 24]);
    const answers: Record<string, number> = {};
    for (let i = 1; i <= 24; i++) answers[`q${i}`] = reversed.has(i) ? 1 : 5;
    // Composure only partly answered with an out-of-range value: that item is ignored (as in the app).
    const partial = { ...answers, q1: 9, q7: 2 };
    await putDoc(p, "player-survey", {
      survey: null,
      mindset: [
        { id: "m1", date: "2026-10-01T10:00:00Z", answers },
        { id: "m2", date: "2026-11-01T10:00:00Z", answers: partial },
      ],
    });
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["player-survey"]);
    const survey = docs["player-survey"] as { survey: unknown; mindset: { scores: Record<string, number> }[] };
    expect(survey.survey).toBeNull();
    expect(survey.mindset[0].scores).toEqual({
      composure: 100,
      resilience: 100,
      focus: 100,
      teamwork: 100,
      leadership: 100,
      drive: 100,
    });
    // composure items: q1 (ignored), q7 reversed 2 -> 4, q13 5, q19 reversed 1 -> 5: mean 14/3 -> (4.667-1)/4*100 = 91.67 -> 92.
    expect(survey.mindset[1].scores.composure).toBe(92);
  });

  it("surveyWithMalformedShapeYieldsNoAnswers", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    await putDoc(p, "player-survey", { survey: { role: "igl" }, mindset: "oops", answers: { q1: 1 } });
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["player-survey"]);
    expect(docs["player-survey"]).toEqual({ survey: { role: "igl" }, mindset: [] });
  });

  it("otherCollectionsPassThroughUnchanged", async () => {
    const a = await makeTeam("Alpha");
    const p = await addUser("p", "teamPlayer", a.teamId);
    const nutrition = { meals: [{ id: "m", calories: 600 }], water: { "2026-10-25": 1500 } };
    await putDoc(p, "nutrition", nutrition);
    const docs = await playerDocuments(deps, await ctxOf(a.staffId), p, ["nutrition"]);
    expect(docs.nutrition).toEqual(nutrition);
  });
});
