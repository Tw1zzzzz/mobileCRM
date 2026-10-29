import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { handle } from "../lib/http";
import { loginHandler } from "../lib/auth/login";
import { refreshHandler } from "../lib/auth/session";
import { registerHandler } from "../lib/auth/register";
import { verifyEmailHandler } from "../lib/auth/verify";
import { deleteMe, getMe, patchMe } from "../lib/me";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const PASSWORD = "secret123";

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

const me = (token?: string) => send(getMe, "GET", "me", undefined, token);
const patch = (body: unknown, token?: string) => send(patchMe, "PATCH", "me", body, token);
const del = (token?: string) => send(deleteMe, "DELETE", "me", undefined, token);

async function signUp(email: string, extra: Record<string, unknown> = {}) {
  const reg = await send(registerHandler, "POST", "auth/register", {
    nickname: "Nova",
    email,
    password: PASSWORD,
    role: "soloPlayer",
    accepted_terms: true,
    accepted_privacy: true,
    accepted_beta_terms: true,
    beta_terms_version: "2026-10-26",
    ...extra,
  });
  expect(reg.status).toBe(201);
  const res = await send(verifyEmailHandler, "POST", "auth/verify-email", {
    email,
    code: deps.mailer.lastCode(email)!,
    password: PASSWORD,
  });
  expect(res.status).toBe(200);
  return res.body.data as {
    access_token: string;
    refresh_token: string;
    user: { id: string };
  };
}

describe("me", () => {
  it("meNeedsBearer", async () => {
    for (const reply of [await me(), await patch({ nickname: "X" }), await del()]) {
      expect(reply.status).toBe(401);
      expect(reply.body.error.code).toBe("unauthorized");
    }
    const bad = await me("not-a-jwt");
    expect(bad.status).toBe(401);
    expect(bad.body.error.code).toBe("unauthorized");
  });

  it("getMeReturnsUser", async () => {
    const session = await signUp("nova@team.gg");
    const res = await me(session.access_token);
    expect(res.status).toBe(200);
    expect(res.body.data).toMatchObject({ id: session.user.id, email: "nova@team.gg", nickname: "Nova" });
    expect(res.body.data).not.toHaveProperty("password_hash");
  });

  it("expiredTokenIsTokenExpired", async () => {
    const session = await signUp("nova@team.gg");
    deps.advance(16 * 60_000);
    const res = await me(session.access_token);
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe("token_expired");
  });

  it("patchNicknameAndFaceit", async () => {
    const session = await signUp("nova@team.gg");
    const token = session.access_token;
    deps.advance(60_000);
    const first = await patch({ nickname: "  Supernova ", faceit_url: "https://www.faceit.com/en/players/nova" }, token);
    expect(first.status).toBe(200);
    expect(first.body.data).toMatchObject({
      nickname: "Supernova",
      faceit_url: "https://www.faceit.com/en/players/nova",
      email: "nova@team.gg",
    });
    const [row] = await deps.sql`select updated_at, created_at from users`;
    expect(row.updated_at.getTime()).toBe(deps.now().getTime());
    expect(row.created_at.getTime()).not.toBe(deps.now().getTime());

    // Absent fields are unchanged; unknown keys are ignored.
    const second = await patch({ nickname: "Nova2", role: "staff", email: "x@y.gg" }, token);
    expect(second.status).toBe(200);
    expect(second.body.data).toMatchObject({
      nickname: "Nova2",
      role: "soloPlayer",
      email: "nova@team.gg",
      faceit_url: "https://www.faceit.com/en/players/nova",
    });

    // An empty body changes nothing.
    const empty = await patch({}, token);
    expect(empty.status).toBe(200);
    expect(empty.body.data.nickname).toBe("Nova2");

    // null and "" clear the link.
    const cleared = await patch({ faceit_url: null }, token);
    expect(cleared.body.data.faceit_url).toBeNull();
    await patch({ faceit_url: "https://faceit.com/x" }, token);
    const blank = await patch({ faceit_url: "" }, token);
    expect(blank.status).toBe(200);
    expect(blank.body.data.faceit_url).toBeNull();
  });

  it("patchRejectsInvalidValues", async () => {
    const session = await signUp("nova@team.gg");
    const token = session.access_token;
    const cases: unknown[] = [
      { nickname: "" },
      { nickname: "   " },
      { nickname: "x".repeat(41) },
      { nickname: 5 },
      { nickname: null },
      { faceit_url: "https://example.com/nova" },
      { faceit_url: "not a url" },
      { faceit_url: 7 },
    ];
    for (const body of cases) {
      const res = await patch(body, token);
      expect(res.status, JSON.stringify(body)).toBe(422);
      expect(res.body.error.code).toBe("validation_failed");
    }
    const [row] = await deps.sql`select nickname, faceit_url from users`;
    expect(row).toMatchObject({ nickname: "Nova", faceit_url: null });
    const bad = await send(patchMe, "PATCH", "me", undefined, token);
    expect(bad.status).toBe(400);
    expect(bad.body.error.code).toBe("bad_request");
  });

  it("deleteRemovesUserAndTokens", async () => {
    const session = await signUp("nova@team.gg");
    const res = await del(session.access_token);
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ success: true, data: {}, error: null });

    expect(await deps.sql`select 1 from users`).toHaveLength(0);
    expect(await deps.sql`select 1 from refresh_tokens`).toHaveLength(0);
    expect(await deps.sql`select 1 from email_codes`).toHaveLength(0);

    const again = await send(loginHandler, "POST", "auth/login", { email: "nova@team.gg", password: PASSWORD });
    expect(again.status).toBe(401);
    const refreshed = await send(refreshHandler, "POST", "auth/refresh", { refresh_token: session.refresh_token });
    expect(refreshed.status).toBe(401);
  });

  it("deletedUsersAccessTokenIsRejected", async () => {
    const session = await signUp("nova@team.gg");
    expect((await del(session.access_token)).status).toBe(200);
    const res = await me(session.access_token);
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe("unauthorized");
  });

  it("deletingStaffFreesPlayers", async () => {
    const staff = await signUp("staff1@team.gg", { role: "staff", team_name: "Alpha" });
    const [team] = await deps.sql`select invite_code from teams`;
    const player = await signUp("p1@team.gg", { role: "teamPlayer", team_code: team.invite_code });
    const before = await me(player.access_token);
    expect(before.body.data).toMatchObject({ role: "teamPlayer", team: "Alpha" });

    expect((await del(staff.access_token)).status).toBe(200);

    expect(await deps.sql`select 1 from teams`).toHaveLength(0);
    const after = await me(player.access_token);
    expect(after.status).toBe(200);
    expect(after.body.data).toMatchObject({ role: "soloPlayer", team: null, team_id: null, invite_code: null });
  });

  it("deletingAPlayerKeepsTheTeam", async () => {
    const staff = await signUp("staff1@team.gg", { role: "staff", team_name: "Alpha" });
    const [team] = await deps.sql`select invite_code from teams`;
    const player = await signUp("p1@team.gg", { role: "teamPlayer", team_code: team.invite_code });

    expect((await del(player.access_token)).status).toBe(200);

    expect(await deps.sql`select 1 from teams`).toHaveLength(1);
    const owner = await me(staff.access_token);
    expect(owner.body.data).toMatchObject({ role: "staff", team: "Alpha" });
  });
});
