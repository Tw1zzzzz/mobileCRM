import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { Roster } from "@/components/staff/players/Roster";
import { TeamManagement } from "@/components/staff/players/TeamManagement";
import { issueSession } from "../lib/auth/tokens";
import { requireStaff, type StaffContext } from "../lib/staff/access";
import { lastActivityLabel, loadPlayers, type PlayersPage } from "../lib/staff/players";
import {
  TEAM_ERROR_TEXT,
  removeMemberCore,
  rotateCodeCore,
} from "../lib/staff/team-management";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();

// The Server Actions run against the test database and the fixed test clock, with a cookie we control.
const session = vi.hoisted(() => ({ token: undefined as string | undefined }));
vi.mock("@/lib/deps", () => ({ defaultDeps: () => deps }));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name === "pulse_staff" && session.token ? { value: session.token } : undefined),
    set: () => undefined,
    delete: () => undefined,
  }),
  headers: async () => new Headers(),
}));

beforeEach(async () => {
  session.token = undefined;
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
    values (${`p${counter}@players.gg`}, ${nickname}, 'x', ${role}, ${teamId}, ${now},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return row.id;
}

async function makeTeam(
  name: string,
  inviteCode: string,
  staffCode: string,
): Promise<{ teamId: string; staffId: string }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values (${name}, ${inviteCode}, ${staffCode}, ${deps.now()})
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

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

/** Text of the markup without tags, to assert on what a person reads. */
function text(markup: string): string {
  return markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
}

async function codesOf(teamId: string): Promise<{ invite_code: string; staff_code: string }> {
  const [row] = await deps.sql<{ invite_code: string; staff_code: string }[]>`
    select invite_code, staff_code from teams where id = ${teamId}`;
  return row;
}

async function roleOf(userId: string): Promise<{ role: string; team_id: string | null }> {
  const [row] = await deps.sql<{ role: string; team_id: string | null }[]>`
    select role, team_id from users where id = ${userId}`;
  return row;
}

/** The URL a Server Action redirected to, taken from the redirect error Next throws. */
async function redirectOf(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (e) {
    const digest = (e as { digest?: unknown }).digest;
    if (typeof digest === "string" && digest.startsWith("NEXT_REDIRECT")) return digest.split(";")[2];
    throw e;
  }
  throw new Error("expected a redirect");
}

// A team with three players in the three readiness bands plus one with nothing synced.
async function seedRoster(): Promise<{ ctx: StaffContext; ids: Record<string, string> }> {
  const { teamId, staffId } = await makeTeam("Alpha", "PALPHA", "SALPHA");
  const vega = await addUser("vega", "teamPlayer", teamId);
  const nova = await addUser("nova", "teamPlayer", teamId);
  const rook = await addUser("rook", "teamPlayer", teamId);
  const ghost = await addUser("ghost", "teamPlayer", teamId);
  // deps.now() is 2026-10-26 12:00 UTC = 15:00 in Moscow
  // vega: sleep 8 h, mood 8, energy 6, cognitive 70 -> 26.25 + 24 + 15 + 21 = 86.25 -> 86
  await putDoc(vega, "sleep", { sessions: [{ id: "s", start: "2026-10-25T20:00:00Z", end: "2026-10-26T04:00:00Z" }] });
  await putDoc(vega, "wellbeing", { mood: [{ id: "m", day: "2026-10-26T08:00:00Z", mood: 8, energy: 6 }] });
  await putDoc(vega, "cognitive", { results: [{ id: "c", date: "2026-10-26T08:00:00Z", score: 70 }] });
  // nova: sleep 7 h, mood 5, energy 5, cognitive 40 -> 18.75 + 15 + 12.5 + 12 = 58.25 -> 58
  await putDoc(nova, "sleep", { sessions: [{ id: "s", start: "2026-10-25T21:00:00Z", end: "2026-10-26T04:00:00Z" }] });
  await putDoc(nova, "wellbeing", { mood: [{ id: "m", day: "2026-10-26T08:00:00Z", mood: 5, energy: 5 }] });
  await putDoc(nova, "cognitive", { results: [{ id: "c", date: "2026-10-26T08:00:00Z", score: 40 }] });
  // rook: mood 2, energy 2 only: (6 + 5) * 115 / 55 = 23.0 -> 23 (risk)
  await putDoc(rook, "wellbeing", { mood: [{ id: "m", day: "2026-10-26T08:00:00Z", mood: 2, energy: 2 }] });
  return { ctx: await ctxOf(staffId), ids: { staff: staffId, vega, nova, rook, ghost, team: teamId } };
}

describe("lastActivityLabel", () => {
  const TZ = "Europe/Moscow";
  const NOW = new Date("2026-10-26T21:30:00Z"); // 00:30 on the 27th in Moscow

  it("saysTodayYesterdayAndDateInTheStaffZone", () => {
    expect(lastActivityLabel(new Date("2026-10-26T21:10:00Z"), NOW, TZ)).toBe("сегодня, 00:10");
    expect(lastActivityLabel(new Date("2026-10-26T20:50:00Z"), NOW, TZ)).toBe("вчера, 23:50");
    expect(lastActivityLabel(new Date("2026-10-03T11:05:00Z"), NOW, TZ)).toBe("3 окт, 14:05");
    expect(lastActivityLabel(new Date("2025-12-31T09:00:00Z"), NOW, TZ)).toBe("31 дек 2025, 12:00");
  });

  it("nothingSyncedIsNoData", () => {
    expect(lastActivityLabel(null, NOW, TZ)).toBe("нет данных");
  });
});

describe("roster", () => {
  it("rosterRowsFromRealData", async () => {
    const { ctx, ids } = await seedRoster();
    const page = await loadPlayers(deps, ctx, {});
    const byName = new Map(page.roster.rows.map((r) => [r.nickname, r]));

    expect([...byName.keys()]).toEqual(["vega", "nova", "rook", "ghost"]);
    expect(byName.get("vega")).toMatchObject({
      id: ids.vega,
      readiness: 86,
      status: "ready",
      sleep: "8ч 00м",
      mood: "8,0",
      cog: "70",
      lastActivity: "сегодня, 15:00",
    });
    expect(byName.get("nova")).toMatchObject({ readiness: 58, status: "doubt", sleep: "7ч 00м", mood: "5,0", cog: "40" });
    expect(byName.get("rook")).toMatchObject({ readiness: 23, status: "risk", sleep: "—", mood: "2,0", cog: "—" });
    expect(byName.get("ghost")).toMatchObject({
      readiness: null,
      status: null,
      sleep: "—",
      mood: "—",
      cog: "—",
      lastActivity: "нет данных",
    });
    expect(page.roster.summary).toEqual({ ready: 1, doubt: 1, risk: 1, noData: 1 });

    const markup = renderToStaticMarkup(<Roster roster={page.roster} />);
    const words = text(markup);
    expect(words).toContain("Готовность сегодня");
    expect(words).toContain("Готовы 1 · под вопросом 1 · риск 1 · нет данных 1");
    for (const nick of ["vega", "nova", "rook", "ghost"]) expect(words).toContain(nick);
    expect(words).toContain("Готов");
    expect(words).toContain("Под вопросом");
    expect(words).toContain("Риск");
    expect(words).toContain("86");
    expect(words).toContain("8ч 00м");
    expect(words).toContain("Сон");
    expect(words).toContain("Настр.");
    expect(words).toContain("Когн.");
    expect(words).toContain("Активность: сегодня, 15:00");
    expect(words).toContain("Активность: нет данных");
    // not links: the player card belongs to plan B
    expect(markup).not.toContain("<a ");
    expect(markup).not.toContain("<button");
    // the board's demo names are gone
    expect(words).not.toMatch(/vexa|frost|Atlant/);
  });

  it("rosterShowsOnlyThisTeamsPlayers", async () => {
    const { ctx } = await seedRoster();
    const other = await makeTeam("Bravo", "PBRAVO", "SBRAVO");
    await addUser("outsider", "teamPlayer", other.teamId);
    await addUser("lonely", "soloPlayer", null);
    const page = await loadPlayers(deps, ctx, {});
    const markup = renderToStaticMarkup(<Roster roster={page.roster} />);
    expect(markup).not.toContain("outsider");
    expect(markup).not.toContain("lonely");
  });

  it("rosterLegendVerbatim", () => {
    const empty: PlayersPage["roster"] = { rows: [], summary: { ready: 0, doubt: 0, risk: 0, noData: 0 } };
    const markup = renderToStaticMarkup(<Roster roster={empty} />);
    const words = text(markup);
    expect(words).toContain(
      "Готовность 0–100 — из сна, настроения, энергии и когнитивного индекса за последние сутки. " +
        "Готов · 70+, под вопросом · 55–69, риск · ниже 55",
    );
    expect(words).toContain("Состав и карточки");
    expect(words).toContain("Питание команды");
    expect(words).toContain("Скоро");
    // «Питание команды» waits for plan D: shown, but neither a link nor a button
    expect(markup).not.toContain("<a ");
    expect(markup).not.toContain("<button");
    expect(markup).toMatch(/aria-disabled="true"[^>]*>[^<]*Питание команды/);
    expect(words).toContain("В команде пока нет игроков");
    expect(words).not.toContain("Готовы 0");
  });
});

describe("team block", () => {
  async function page(params: Record<string, string> = {}): Promise<{ page: PlayersPage; ids: Record<string, string> }> {
    const { ctx, ids } = await seedRoster();
    const second = await addUser("coach", "staff", ids.team);
    return { page: await loadPlayers(deps, ctx, params), ids: { ...ids, coach: second } };
  }

  it("teamBlockShowsBothCodesAndMembers", async () => {
    const { page: p, ids } = await page();
    const markup = renderToStaticMarkup(<TeamManagement team={p.team} confirm={null} error={null} />);
    const words = text(markup);
    expect(markup).toContain('id="team"');
    expect(words).toContain("Команда");
    expect(words).toContain("PALPHA");
    expect(words).toContain("SALPHA");
    expect(words).toContain("Код игрока");
    expect(words).toContain("Код стаффа");
    expect((markup.match(/>Копировать</g) ?? []).length).toBe(2);
    expect((markup.match(/>Новый код</g) ?? []).length).toBe(2);
    expect(words).toContain("Alpha-staff");
    expect(words).toContain("coach");
    for (const nick of ["vega", "nova", "rook", "ghost"]) expect(words).toContain(nick);
    expect((markup.match(/>Убрать из команды</g) ?? []).length).toBe(6);
    expect(p.team.selfId).toBe(ids.staff);
    expect(words).toContain("(вы)");
    // nothing asks for confirmation until a button was pressed
    expect(words).not.toContain("Продолжить?");
    expect(words).not.toContain("Нельзя убрать последнего стаффа команды");
  });

  it("rotateAsksForConfirmationFirst", async () => {
    const { page: p } = await page({ confirm: "rotate-staff" });
    expect(p.confirm).toEqual({ kind: "rotate", code: "staff" });
    const markup = renderToStaticMarkup(<TeamManagement team={p.team} confirm={p.confirm} error={null} />);
    expect(text(markup)).toContain("Старый код перестанет работать. Продолжить?");
    expect(markup).toContain('name="kind" value="staff"');
    expect(markup).toContain('href="/staff/players#team"'); // «Отмена»
    expect((markup.match(/Старый код перестанет работать/g) ?? []).length).toBe(1);
  });

  it("removeAsksForConfirmationWithTheNickname", async () => {
    const { ctx, ids } = await seedRoster();
    const p = await loadPlayers(deps, ctx, { confirm: `remove-${ids.vega}` });
    expect(p.confirm).toEqual({ kind: "remove", member: { id: ids.vega, nickname: "vega" } });
    const markup = renderToStaticMarkup(<TeamManagement team={p.team} confirm={p.confirm} error={null} />);
    expect(text(markup)).toContain("vega станет соло-игроком и пропадёт из панели. Продолжить?");
    expect(markup).toContain(`name="memberId" value="${ids.vega}"`);
    expect((markup.match(/станет соло-игроком/g) ?? []).length).toBe(1);
  });

  it("confirmOfAnotherTeamsMemberIsIgnored", async () => {
    const { ctx } = await seedRoster();
    const other = await makeTeam("Bravo", "PBRAVO", "SBRAVO");
    const outsider = await addUser("outsider", "teamPlayer", other.teamId);
    for (const confirm of [`remove-${outsider}`, "remove-", "remove-../x", "rotate-admin", "garbage", ["rotate-staff", "x"]]) {
      const p = await loadPlayers(deps, ctx, { confirm });
      if (Array.isArray(confirm)) expect(p.confirm).toEqual({ kind: "rotate", code: "staff" });
      else expect(p.confirm).toBeNull();
    }
  });

  it("lastStaffErrorIsShownInline", async () => {
    const { ctx } = await seedRoster();
    const p = await loadPlayers(deps, ctx, { error: "last-staff" });
    expect(p.error).toBe("last-staff");
    const markup = renderToStaticMarkup(<TeamManagement team={p.team} confirm={null} error={p.error} />);
    expect(text(markup)).toContain("Нельзя убрать последнего стаффа команды");
    expect(markup).toContain('role="alert"');
    // unknown error tokens show nothing
    expect((await loadPlayers(deps, ctx, { error: "<script>" })).error).toBeNull();
  });
});

describe("rotate code", () => {
  it("rotateActionChangesCode", async () => {
    const { ctx, ids } = await seedRoster();
    const before = await codesOf(ids.team);

    expect(await rotateCodeCore(deps, ctx, form({ kind: "player" }))).toEqual({ ok: true });
    const afterPlayer = await codesOf(ids.team);
    expect(afterPlayer.invite_code).not.toBe(before.invite_code);
    expect(afterPlayer.invite_code).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
    expect(afterPlayer.staff_code).toBe(before.staff_code);

    expect(await rotateCodeCore(deps, ctx, form({ kind: "staff" }))).toEqual({ ok: true });
    const afterStaff = await codesOf(ids.team);
    expect(afterStaff.staff_code).not.toBe(before.staff_code);
    expect(afterStaff.invite_code).toBe(afterPlayer.invite_code);

    // the old code stops working at once
    const old = await deps.sql`select 1 from teams where invite_code = ${before.invite_code} or staff_code = ${before.staff_code}`;
    expect(old).toHaveLength(0);
  });

  it("rotateIgnoresATeamIdInTheForm", async () => {
    const { ctx, ids } = await seedRoster();
    const other = await makeTeam("Bravo", "PBRAVO", "SBRAVO");
    expect(await rotateCodeCore(deps, ctx, form({ kind: "player", teamId: other.teamId, team_id: other.teamId }))).toEqual({
      ok: true,
    });
    expect((await codesOf(other.teamId)).invite_code).toBe("PBRAVO");
    expect((await codesOf(ids.team)).invite_code).not.toBe("PALPHA");
  });

  it("rotateForATeamThatNoLongerExistsIsAnInlineFailure", async () => {
    const { ctx, ids } = await seedRoster();
    // the team is gone (e.g. deleted in another tab) while the session still carries its id
    const gone = { ...ctx, teamId: "00000000-0000-4000-8000-000000000000" };
    for (const kind of ["player", "staff"]) {
      expect(await rotateCodeCore(deps, gone, form({ kind }))).toEqual({ ok: false, error: "failed" });
    }
    expect(await codesOf(ids.team)).toEqual({ invite_code: "PALPHA", staff_code: "SALPHA" });
  });

  it("unknownKindChangesNothing", async () => {
    const { ctx, ids } = await seedRoster();
    for (const kind of ["", "admin", "both"]) {
      expect(await rotateCodeCore(deps, ctx, form({ kind }))).toEqual({ ok: false, error: "failed" });
    }
    expect(await rotateCodeCore(deps, ctx, new FormData())).toEqual({ ok: false, error: "failed" });
    expect(await codesOf(ids.team)).toEqual({ invite_code: "PALPHA", staff_code: "SALPHA" });
  });
});

describe("remove member", () => {
  it("removeActionRemovesPlayer", async () => {
    const { ctx, ids } = await seedRoster();
    expect(await removeMemberCore(deps, ctx, form({ memberId: ids.vega }))).toEqual({ ok: true });
    expect(await roleOf(ids.vega)).toEqual({ role: "soloPlayer", team_id: null });
    const page = await loadPlayers(deps, ctx, {});
    expect(page.roster.rows.map((r) => r.nickname)).toEqual(["nova", "rook", "ghost"]);
    expect(page.team.players.map((m) => m.nickname)).toEqual(["nova", "rook", "ghost"]);
  });

  it("removeLastStaffShowsError", async () => {
    const { ctx, ids } = await seedRoster();
    expect(await removeMemberCore(deps, ctx, form({ memberId: ids.staff }))).toEqual({
      ok: false,
      error: "last-staff",
    });
    expect(await roleOf(ids.staff)).toEqual({ role: "staff", team_id: ids.team });
    expect(TEAM_ERROR_TEXT["last-staff"]).toBe("Нельзя убрать последнего стаффа команды");
  });

  it("aStaffMemberMayLeaveWhenAnotherStaffRemains", async () => {
    const { ctx, ids } = await seedRoster();
    const coach = await addUser("coach", "staff", ids.team);
    expect(await removeMemberCore(deps, ctx, form({ memberId: ids.staff }))).toEqual({ ok: true });
    expect(await roleOf(ids.staff)).toEqual({ role: "soloPlayer", team_id: null });
    expect(await roleOf(coach)).toEqual({ role: "staff", team_id: ids.team });
  });

  it("garbageMemberIdChangesNothing", async () => {
    const { ctx, ids } = await seedRoster();
    for (const memberId of ["", "nobody", "../x", "00000000-0000-0000-0000-000000000000"]) {
      expect(await removeMemberCore(deps, ctx, form({ memberId }))).toEqual({ ok: false, error: "failed" });
    }
    expect(await removeMemberCore(deps, ctx, new FormData())).toEqual({ ok: false, error: "failed" });
    expect(await roleOf(ids.vega)).toEqual({ role: "teamPlayer", team_id: ids.team });
  });
});

describe("actions require staff of that team", () => {
  it("actionsRequireStaffOfThatTeam", async () => {
    const mine = await seedRoster();
    const other = await makeTeam("Bravo", "PBRAVO", "SBRAVO");
    const theirPlayer = await addUser("theirs", "teamPlayer", other.teamId);
    const theirStaffCtx = await ctxOf(other.staffId);

    // A member id of another team does not resolve in the caller's team, and nothing changes.
    expect(await removeMemberCore(deps, mine.ctx, form({ memberId: theirPlayer }))).toEqual({
      ok: false,
      error: "failed",
    });
    expect(await removeMemberCore(deps, mine.ctx, form({ memberId: other.staffId }))).toEqual({
      ok: false,
      error: "failed",
    });
    expect(await roleOf(theirPlayer)).toEqual({ role: "teamPlayer", team_id: other.teamId });
    expect(await roleOf(other.staffId)).toEqual({ role: "staff", team_id: other.teamId });

    // and the other way round: their staff cannot touch my players
    expect(await removeMemberCore(deps, theirStaffCtx, form({ memberId: mine.ids.vega }))).toEqual({
      ok: false,
      error: "failed",
    });
    expect(await roleOf(mine.ids.vega)).toEqual({ role: "teamPlayer", team_id: mine.ids.team });
  });

  it("loggedOutCallerCannotRunAnAction", async () => {
    const { ids } = await seedRoster();
    const { removeMemberAction, rotateCodeAction } = await import("../app/staff/actions");
    session.token = undefined;
    expect(await redirectOf(() => rotateCodeAction(form({ kind: "player" })))).toBe("/staff/login");
    expect(await redirectOf(() => removeMemberAction(form({ memberId: ids.vega })))).toBe("/staff/login");
    expect(await codesOf(ids.team)).toEqual({ invite_code: "PALPHA", staff_code: "SALPHA" });
    expect(await roleOf(ids.vega)).toEqual({ role: "teamPlayer", team_id: ids.team });
  });

  it("nonStaffCallerCannotRunAnAction", async () => {
    const { ids } = await seedRoster();
    const { removeMemberAction, rotateCodeAction } = await import("../app/staff/actions");
    // a player of the very team, and a solo player
    for (const user of [ids.vega, await addUser("solo", "soloPlayer", null)]) {
      session.token = (await issueSession(deps, user)).refresh_token;
      expect(await redirectOf(() => rotateCodeAction(form({ kind: "staff" })))).toBe("/staff/no-access");
      expect(await redirectOf(() => removeMemberAction(form({ memberId: ids.nova })))).toBe("/staff/no-access");
    }
    expect(await codesOf(ids.team)).toEqual({ invite_code: "PALPHA", staff_code: "SALPHA" });
    expect(await roleOf(ids.nova)).toEqual({ role: "teamPlayer", team_id: ids.team });
  });

  it("actionsTakeTheTeamFromTheSessionAndRedirectToAConstant", async () => {
    const mine = await seedRoster();
    const other = await makeTeam("Bravo", "PBRAVO", "SBRAVO");
    const theirPlayer = await addUser("theirs", "teamPlayer", other.teamId);
    const { removeMemberAction, rotateCodeAction } = await import("../app/staff/actions");
    session.token = (await issueSession(deps, mine.ids.staff)).refresh_token;

    const rotated = await redirectOf(() => rotateCodeAction(form({ kind: "player", teamId: other.teamId, next: "https://evil.example" })));
    expect(rotated).toBe("/staff/players#team");
    expect((await codesOf(other.teamId)).invite_code).toBe("PBRAVO");
    expect((await codesOf(mine.ids.team)).invite_code).not.toBe("PALPHA");

    const foreign = await redirectOf(() => removeMemberAction(form({ memberId: theirPlayer, next: "https://evil.example" })));
    expect(foreign).toBe("/staff/players?error=failed#team");
    expect(await roleOf(theirPlayer)).toEqual({ role: "teamPlayer", team_id: other.teamId });

    const last = await redirectOf(() => removeMemberAction(form({ memberId: mine.ids.staff })));
    expect(last).toBe("/staff/players?error=last-staff#team");

    const removed = await redirectOf(() => removeMemberAction(form({ memberId: mine.ids.vega })));
    expect(removed).toBe("/staff/players#team");
    expect(await roleOf(mine.ids.vega)).toEqual({ role: "soloPlayer", team_id: null });
  });

  it("aStaffMemberWhoRemovesThemselvesLosesAccess", async () => {
    const { ids } = await seedRoster();
    await addUser("coach", "staff", ids.team);
    const { removeMemberAction } = await import("../app/staff/actions");
    session.token = (await issueSession(deps, ids.staff)).refresh_token;
    expect(await redirectOf(() => removeMemberAction(form({ memberId: ids.staff })))).toBe("/staff/players#team");
    // the next page load goes through the normal guard
    const { requireStaffContext } = await import("../lib/staff/current");
    expect(await redirectOf(() => requireStaffContext())).toBe("/staff/no-access");
  });
});
