import { requireStaff, type StaffContext } from "../lib/staff/access";
import type { TestDeps } from "./helpers";

/**
 * A team for the player-card tests. The fixed test clock is 2026-10-26 12:00 UTC = 15:00 in Moscow, so "today"
 * is 2026-10-26 and a 30-day period starts on 2026-09-27.
 *
 *  - vega: cognitive 70 (20 Oct, two tests), 50 (22 Oct), 90 (24 Oct), 70 (26 Oct); game tests on 22 and 25 Oct;
 *    sleep 8 h ending 24 Oct and 5.5 h ending 26 Oct; mood and energy on 24 and 26 Oct.
 *  - nova: cognitive 80 (24 Oct) and 60 (26 Oct) only.
 *  - ghost: joined, never synced anything.
 * Team Charlie has staff only. Another team (Bravo) has "zed-outsider"; "solo-loner" belongs to no team.
 */
export interface CardTeam {
  ctx: StaffContext;
  ids: { staff: string; vega: string; nova: string; ghost: string; outsider: string; solo: string; otherStaff: string; loneStaff: string; team: string };
}

type Role = "soloPlayer" | "teamPlayer" | "staff";

let counter = 0;

async function addUser(deps: TestDeps, nickname: string, role: Role, teamId: string | null): Promise<string> {
  counter += 1;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`c${counter}@card.gg`}, ${nickname}, 'x', ${role}, ${teamId}, ${now},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return row.id;
}

async function makeTeam(deps: TestDeps, name: string, invite: string): Promise<{ teamId: string; staffId: string }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values (${name}, ${invite}, ${"S" + invite.slice(1)}, ${deps.now()})
    returning id`;
  const staffId = await addUser(deps, `${name}-staff`, "staff", t.id);
  await deps.sql`update teams set owner_id = ${staffId} where id = ${t.id}`;
  return { teamId: t.id, staffId };
}

export async function putDoc(deps: TestDeps, userId: string, collection: string, document: unknown): Promise<void> {
  await deps.sql`
    insert into sync_documents (user_id, collection, version, document, updated_at)
    values (${userId}, ${collection}, 1, ${deps.sql.json(document as never)}, ${deps.now()})
    on conflict (user_id, collection) do update set document = excluded.document`;
}

export async function seedCardTeam(deps: TestDeps): Promise<CardTeam> {
  const alpha = await makeTeam(deps, "Alpha", "PALPHA");
  const bravo = await makeTeam(deps, "Bravo", "PBRAVO");
  const charlie = await makeTeam(deps, "Charlie", "PCHARL");
  const vega = await addUser(deps, "vega", "teamPlayer", alpha.teamId);
  const nova = await addUser(deps, "nova", "teamPlayer", alpha.teamId);
  const ghost = await addUser(deps, "ghost", "teamPlayer", alpha.teamId);
  const outsider = await addUser(deps, "zed-outsider", "teamPlayer", bravo.teamId);
  const solo = await addUser(deps, "solo-loner", "soloPlayer", null);

  await putDoc(deps, vega, "cognitive", {
    results: [
      { id: "c1", kind: "reaction", date: "2026-10-20T08:00:00Z", score: 60, metric: "310 мс" },
      { id: "c2", kind: "goNoGo", date: "2026-10-20T09:00:00Z", score: 80, metric: "90%" },
      { id: "c3", kind: "stroop", date: "2026-10-22T08:00:00Z", score: 50, metric: "58%" },
      { id: "c4", kind: "memory", date: "2026-10-24T08:00:00Z", score: 90, metric: "8" },
      { id: "c5", kind: "aim", date: "2026-10-26T08:00:00Z", score: 70, metric: "70%" },
    ],
    gameResults: [
      { id: "g1", date: "2026-10-22T08:30:00Z", phase: "beforeTraining", focus: 9, fatigue: 2, readiness: 8 },
      { id: "g2", date: "2026-10-22T15:00:00Z", phase: "afterTraining", focus: 7, fatigue: 8, readiness: 5 },
      { id: "g3", date: "2026-10-25T08:00:00Z", phase: "beforeTraining", focus: 6, fatigue: 9, readiness: 4 },
    ],
  });
  await putDoc(deps, vega, "sleep", {
    sessions: [
      { id: "s1", start: "2026-10-23T20:00:00Z", end: "2026-10-24T04:00:00Z" }, // 23:00 -> 07:00 Moscow, 8 h
      { id: "s2", start: "2026-10-25T20:30:00Z", end: "2026-10-26T02:00:00Z" }, // 23:30 -> 05:00, 5.5 h
    ],
  });
  await putDoc(deps, vega, "wellbeing", {
    mood: [
      { id: "m1", day: "2026-10-24T08:00:00Z", mood: 8, energy: 6 },
      { id: "m2", day: "2026-10-26T08:00:00Z", mood: 4, energy: 3 },
    ],
  });
  await putDoc(deps, nova, "cognitive", {
    results: [
      { id: "n1", kind: "reaction", date: "2026-10-24T08:00:00Z", score: 80, metric: "250 мс" },
      { id: "n2", kind: "aim", date: "2026-10-26T08:00:00Z", score: 60, metric: "60%" },
    ],
  });
  await putDoc(deps, outsider, "cognitive", {
    results: [{ id: "o1", kind: "reaction", date: "2026-10-26T08:00:00Z", score: 99, metric: "x" }],
  });

  return {
    ctx: await requireStaff(deps, alpha.staffId),
    ids: {
      staff: alpha.staffId, vega, nova, ghost, outsider, solo, otherStaff: bravo.staffId, loneStaff: charlie.staffId, team: alpha.teamId,
    },
  };
}
