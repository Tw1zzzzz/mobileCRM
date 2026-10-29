import { readFile } from "node:fs/promises";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { registerHandler } from "../lib/auth/register";
import { verifyEmailHandler } from "../lib/auth/verify";
import { handle } from "../lib/http";
import { deleteMe, getMe } from "../lib/me";
import { createTeam, findTeamByStaffCode } from "../lib/teams";
import { removeMember, rotateCode, teamMembers } from "../lib/team/members";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const PASSWORD = "secret123";
const CODE = /^[A-HJ-NP-Z2-9]{6}$/;

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

interface Reply {
  status: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  body: any;
}

async function send(
  fn: (req: Request, d: typeof deps) => Promise<Response>,
  method: string,
  path: string,
  body?: unknown,
  token?: string,
): Promise<Reply> {
  const req = new Request(`http://localhost/v1/${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { "content-type": "application/json" }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const res = await handle(() => fn(req, deps));
  return { status: res.status, body: await res.json() };
}

const register = (email: string, extra: Record<string, unknown>) =>
  send(registerHandler, "POST", "auth/register", {
    nickname: email.split("@")[0],
    email,
    password: PASSWORD,
    role: "soloPlayer",
    accepted_terms: true,
    accepted_privacy: true,
    accepted_beta_terms: true,
    beta_terms_version: "2026-10-26",
    ...extra,
  });

interface Session {
  id: string;
  token: string;
}

async function signUp(email: string, extra: Record<string, unknown> = {}): Promise<Session> {
  const reg = await register(email, extra);
  expect(reg.status, JSON.stringify(reg.body)).toBe(201);
  const res = await send(verifyEmailHandler, "POST", "auth/verify-email", {
    email,
    code: deps.mailer.lastCode(email)!,
    password: PASSWORD,
  });
  expect(res.status).toBe(200);
  // Each account gets its own moment, so "longest-standing" is well defined.
  deps.advance(60_000);
  return { id: res.body.data.user.id, token: res.body.data.access_token };
}

const me = (s: Session) => send(getMe, "GET", "me", undefined, s.token);

async function codesOf(): Promise<{ id: string; invite_code: string; staff_code: string; owner_id: string }> {
  const [row] = await deps.sql`select id, invite_code, staff_code, owner_id from teams`;
  return row as never;
}

async function team() {
  const owner = await signUp("owner@team.gg", { role: "staff", team_name: "Alpha" });
  return { owner, ...(await codesOf()) };
}

describe("staff registration", () => {
  it("staffJoinsWithStaffCode", async () => {
    const t = await team();
    const second = await signUp("second@team.gg", { role: "staff", staff_code: ` ${t.staff_code.toLowerCase()} ` });
    const view = (await me(second)).body.data;
    expect(view).toMatchObject({ role: "staff", team: "Alpha", team_id: t.id });
    expect(view.invite_code).toBe(t.invite_code);
    expect(view.staff_code).toBe(t.staff_code);
    const teams = await deps.sql`select owner_id from teams`;
    expect(teams).toHaveLength(1);
    expect(teams[0].owner_id).toBe(t.owner.id);
  });

  it("staffNeedsExactlyOneOfNameOrCode", async () => {
    const t = await team();
    const both = await register("a@team.gg", { role: "staff", team_name: "Beta", staff_code: t.staff_code });
    expect(both.status).toBe(422);
    expect(both.body.error.code).toBe("validation_failed");
    const neither = await register("b@team.gg", { role: "staff" });
    expect(neither.status).toBe(422);
    expect(neither.body.error.code).toBe("validation_failed");
    const blank = await register("c@team.gg", { role: "staff", team_name: " ", staff_code: " " });
    expect(blank.status).toBe(422);
    expect(await deps.sql`select 1 from teams`).toHaveLength(1);
    expect(await deps.sql`select 1 from users where email in ('a@team.gg','b@team.gg','c@team.gg')`).toHaveLength(0);
  });

  it("unknownStaffCodeIsTeamNotFound", async () => {
    await team();
    const res = await register("a@team.gg", { role: "staff", staff_code: "ZZZZZZ" });
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe("team_not_found");
  });

  it("playerCodeDoesNotJoinAsStaff", async () => {
    const t = await team();
    const res = await register("a@team.gg", { role: "staff", staff_code: t.invite_code });
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe("team_not_found");
    // ...and the staff code does not join as a player either.
    const asPlayer = await register("p@team.gg", { role: "teamPlayer", team_code: t.staff_code });
    expect(asPlayer.status).toBe(404);
  });

  it("staffCodeIsIgnoredForOtherRoles", async () => {
    const solo = await register("a@team.gg", { staff_code: "ZZZZZZ" });
    expect(solo.status).toBe(201);
  });

  it("reRegisteringAsStaffJoinKeepsNoOldTeam", async () => {
    const t = await team();
    await register("x@team.gg", { role: "staff", team_name: "Temp" });
    expect(await deps.sql`select 1 from teams`).toHaveLength(2);
    const again = await register("x@team.gg", { role: "staff", staff_code: t.staff_code });
    expect(again.status).toBe(201);
    expect(await deps.sql`select 1 from teams`).toHaveLength(1);
    const [row] = await deps.sql`select team_id from users where email = 'x@team.gg'`;
    expect(row.team_id).toBe(t.id);
  });

  it("findTeamByStaffCodeIsCaseInsensitive", async () => {
    const t = await team();
    expect(await findTeamByStaffCode(deps, ` ${t.staff_code.toLowerCase()}`)).toEqual({ id: t.id, name: "Alpha" });
    expect(await findTeamByStaffCode(deps, t.invite_code)).toBeNull();
  });
});

describe("codes", () => {
  it("newTeamHasTwoDifferentValidCodes", async () => {
    const t = await team();
    expect(t.invite_code).toMatch(CODE);
    expect(t.staff_code).toMatch(CODE);
    expect(t.staff_code).not.toBe(t.invite_code);
  });

  it("rotateCodeInvalidatesOld", async () => {
    const t = await team();
    for (const kind of ["player", "staff"] as const) {
      const before = await codesOf();
      const fresh = await rotateCode(deps, t.id, kind);
      expect(fresh).toMatch(CODE);
      const old = kind === "player" ? before.invite_code : before.staff_code;
      expect(fresh).not.toBe(old);

      const body = kind === "player" ? { role: "teamPlayer", team_code: old } : { role: "staff", staff_code: old };
      const stale = await register(`${kind}-old@team.gg`, body);
      expect(stale.status).toBe(404);
      expect(stale.body.error.code).toBe("team_not_found");
      const good = await register(
        `${kind}-new@team.gg`,
        kind === "player" ? { role: "teamPlayer", team_code: fresh } : { role: "staff", staff_code: fresh },
      );
      expect(good.status).toBe(201);
    }
    // Rotating one kind leaves the other untouched.
    const rows = await codesOf();
    expect(rows.invite_code).not.toBe(rows.staff_code);
  });

  it("rotateCodeOfUnknownTeamIsNotFound", async () => {
    await expect(rotateCode(deps, "00000000-0000-4000-8000-000000000000", "staff")).rejects.toMatchObject({
      code: "not_found",
      status: 404,
    });
  });

  it("codesUniqueAcrossBothColumns", async () => {
    // Seeded team holds AAAAAA (player) and BBBBBB (staff); the stubbed source offers those first.
    await deps.sql`
      insert into teams (name, invite_code, staff_code, created_at) values ('Seed', 'AAAAAA', 'BBBBBB', ${deps.now()})`;
    const owner = await signUp("o@team.gg");
    // Six of each letter in turn: AAAAAA and BBBBBB are taken (one per column), so CCCCCC and DDDDDD win.
    const sixOfEach = () => {
      let n = 0;
      return () => Math.floor(n++ / 6);
    };
    const created = await createTeam(deps, "Beta", owner.id, sixOfEach());
    expect([created.invite_code, created.staff_code]).toEqual(["CCCCCC", "DDDDDD"]);

    // Rotating onto a code that is the other kind's current value is refused too.
    // Offered in turn: CCCCCC (its own player code), DDDDDD (its current staff code), then EEEEEE.
    const fromC = (() => {
      let n = 0;
      return () => 2 + Math.floor(n++ / 6);
    })();
    expect(await rotateCode(deps, created.id, "staff", fromC)).toBe("EEEEEE");
  });
});

describe("members", () => {
  it("teamMembersListsVerifiedMembersOldestFirst", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    const staff = await signUp("s@team.gg", { role: "staff", staff_code: t.staff_code });
    await register("pending@team.gg", { role: "teamPlayer", team_code: t.invite_code }); // never verified
    const solo = await signUp("solo@team.gg");
    const list = await teamMembers(deps, t.id);
    expect(list.map((m) => m.id)).toEqual([t.owner.id, player.id, staff.id]);
    expect(list.map((m) => m.role)).toEqual(["staff", "teamPlayer", "staff"]);
    expect(list[1]).toMatchObject({ nickname: "p" });
    expect(list[0].joined_at).toBeInstanceOf(Date);
    expect(list.some((m) => m.id === solo.id)).toBe(false);
    expect(Object.keys(list[0]).sort()).toEqual(["id", "joined_at", "nickname", "role"]);
  });

  it("removePlayerMakesSolo", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    await removeMember(deps, t.id, t.owner.id, player.id);
    expect((await me(player)).body.data).toMatchObject({ role: "soloPlayer", team: null, team_id: null });
    expect(await teamMembers(deps, t.id)).toHaveLength(1);
  });

  it("removeStaffMakesSolo", async () => {
    const t = await team();
    const staff = await signUp("s@team.gg", { role: "staff", staff_code: t.staff_code });
    await removeMember(deps, t.id, t.owner.id, staff.id);
    const view = (await me(staff)).body.data;
    expect(view).toMatchObject({ role: "soloPlayer", team: null, team_id: null, invite_code: null, staff_code: null });
    expect((await codesOf()).owner_id).toBe(t.owner.id);
  });

  it("removingTheOwnerHandsOwnershipToTheLongestStandingStaff", async () => {
    const t = await team();
    const first = await signUp("s1@team.gg", { role: "staff", staff_code: t.staff_code });
    await signUp("s2@team.gg", { role: "staff", staff_code: t.staff_code });
    await removeMember(deps, t.id, first.id, t.owner.id);
    expect((await codesOf()).owner_id).toBe(first.id);
    // The former owner deleting their (now solo) account no longer touches the team.
    expect((await send(deleteMe, "DELETE", "me", undefined, t.owner.token)).status).toBe(200);
    expect(await deps.sql`select 1 from teams`).toHaveLength(1);
  });

  it("lastStaffCannotLeave", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    await expect(removeMember(deps, t.id, t.owner.id, t.owner.id)).rejects.toMatchObject({
      code: "validation_failed",
      status: 422,
      publicMessage: "The last staff member cannot leave the team.",
    });
    expect((await me(t.owner)).body.data.role).toBe("staff");
    expect((await me(player)).body.data.team_id).toBe(t.id);

    // A staff member who never verified their email does not count as someone who can run the team.
    await register("ghost@team.gg", { role: "staff", staff_code: t.staff_code });
    await expect(removeMember(deps, t.id, t.owner.id, t.owner.id)).rejects.toMatchObject({ code: "validation_failed" });
  });

  it("cannotRemoveMemberOfOtherTeam", async () => {
    const t = await team();
    const other = await signUp("other@team.gg", { role: "staff", team_name: "Beta" });
    const [otherTeam] = await deps.sql`select id from teams where name = 'Beta'`;
    const theirPlayer = await signUp("op@team.gg", {
      role: "teamPlayer",
      team_code: (await deps.sql`select invite_code from teams where id = ${otherTeam.id}`)[0].invite_code,
    });
    await expect(removeMember(deps, t.id, t.owner.id, theirPlayer.id)).rejects.toMatchObject({
      code: "not_found",
      status: 404,
    });
    await expect(removeMember(deps, t.id, t.owner.id, other.id)).rejects.toMatchObject({ code: "not_found" });
    await expect(removeMember(deps, t.id, t.owner.id, "00000000-0000-4000-8000-000000000000")).rejects.toMatchObject({
      code: "not_found",
    });
    expect((await me(theirPlayer)).body.data).toMatchObject({ role: "teamPlayer", team_id: otherTeam.id });
  });

  it("onlyStaffOfTheTeamCanRemove", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    const stranger = await signUp("x@team.gg", { role: "staff", team_name: "Beta" });
    for (const actor of [player.id, stranger.id]) {
      await expect(removeMember(deps, t.id, actor, player.id)).rejects.toMatchObject({ code: "not_found" });
    }
    expect((await me(player)).body.data.team_id).toBe(t.id);
  });
});

describe("deleting an account", () => {
  it("deleteStaffKeepsTeamWhenOthersRemain", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    const s1 = await signUp("s1@team.gg", { role: "staff", staff_code: t.staff_code });
    await signUp("s2@team.gg", { role: "staff", staff_code: t.staff_code });

    expect((await send(deleteMe, "DELETE", "me", undefined, t.owner.token)).status).toBe(200);

    const after = await codesOf();
    expect(after.id).toBe(t.id);
    expect(after.owner_id).toBe(s1.id);
    expect((await me(player)).body.data).toMatchObject({ role: "teamPlayer", team_id: t.id });
    expect((await me(s1)).body.data).toMatchObject({ role: "staff", team: "Alpha" });
  });

  it("deletingANonOwnerStaffChangesNothingElse", async () => {
    const t = await team();
    const s1 = await signUp("s1@team.gg", { role: "staff", staff_code: t.staff_code });
    expect((await send(deleteMe, "DELETE", "me", undefined, s1.token)).status).toBe(200);
    expect((await codesOf()).owner_id).toBe(t.owner.id);
  });

  it("deleteLastStaffDeletesTeam", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    // An unverified staff member is not a successor: the team still goes away.
    await register("ghost@team.gg", { role: "staff", staff_code: t.staff_code });

    expect((await send(deleteMe, "DELETE", "me", undefined, t.owner.token)).status).toBe(200);

    expect(await deps.sql`select 1 from teams`).toHaveLength(0);
    expect((await me(player)).body.data).toMatchObject({ role: "soloPlayer", team: null, team_id: null });
    const [ghost] = await deps.sql`select role, team_id from users where email = 'ghost@team.gg'`;
    expect(ghost).toMatchObject({ role: "soloPlayer", team_id: null });
  });
});

describe("me", () => {
  it("meShowsBothCodesToStaff", async () => {
    const t = await team();
    const owner = (await me(t.owner)).body.data;
    expect(owner.invite_code).toBe(t.invite_code);
    expect(owner.staff_code).toBe(t.staff_code);
    // Every staff member sees both codes, not only the owner.
    const second = await signUp("s@team.gg", { role: "staff", staff_code: t.staff_code });
    const view = (await me(second)).body.data;
    expect(view.invite_code).toBe(t.invite_code);
    expect(view.staff_code).toBe(t.staff_code);
  });

  it("playersAndSolosSeeNoCodes", async () => {
    const t = await team();
    const player = await signUp("p@team.gg", { role: "teamPlayer", team_code: t.invite_code });
    const solo = await signUp("solo@team.gg");
    for (const s of [player, solo]) {
      const view = (await me(s)).body.data;
      expect(view.invite_code).toBeNull();
      expect(view.staff_code).toBeNull();
    }
  });
});

describe("migration 006", () => {
  it("backfillGivesEveryExistingTeamAValidUniqueCode", async () => {
    const sql = await readFile(new URL("../db/migrations/006_team_staff.sql", import.meta.url), "utf8");
    const rollback = new Error("rollback");
    let codes: string[] = [];
    await deps.sql
      .begin(async (tx) => {
        // Put the schema back the way it was before 006, with teams that already exist.
        await tx.unsafe("alter table teams drop column staff_code");
        for (let i = 0; i < 40; i++) {
          await tx`insert into teams (name, invite_code, created_at)
                   values (${"T" + i}, ${"TEST" + String(i).padStart(2, "0")}, ${deps.now()})`;
        }
        await tx.unsafe(sql);
        const rows = await tx<{ staff_code: string }[]>`select staff_code from teams`;
        codes = rows.map((r) => r.staff_code);
        throw rollback;
      })
      .catch((e) => {
        if (e !== rollback) throw e;
      });
    expect(codes).toHaveLength(40);
    expect(new Set(codes).size).toBe(40);
    for (const c of codes) expect(c).toMatch(CODE);
    // The rollback restored the migrated schema.
    expect(await deps.sql`select staff_code from teams`).toHaveLength(0);
  });
});
