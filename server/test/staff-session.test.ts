import { createHash } from "node:crypto";
import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { loginHandler } from "../lib/auth/login";
import { hashPassword } from "../lib/auth/passwords";
import { ApiError, handle } from "../lib/http";
import { IDLE } from "../lib/staff/form-state";
import {
  STAFF_COOKIE,
  resolveSession,
  staffCookieOptions,
  staffResetConfirm,
  staffResetRequest,
  staffSession,
  staffSignIn,
  staffSignOut,
} from "../lib/staff/session";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();

// The sign-in Server Action runs against the test database with a cookie jar we control.
const jar = vi.hoisted(() => ({ cookie: undefined as string | undefined, set: [] as string[] }));
vi.mock("@/lib/deps", () => ({ defaultDeps: () => deps }));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name === "pulse_staff" && jar.cookie ? { value: jar.cookie } : undefined),
    set: (_name: string, value: string) => void jar.set.push(value),
    delete: () => undefined,
  }),
  headers: async () => new Headers({ "x-forwarded-for": "6.6.6.6" }),
}));
const PASSWORD = "secret123";
const DAY = 24 * 60 * 60 * 1000;

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  jar.cookie = undefined;
  jar.set.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

type Role = "soloPlayer" | "teamPlayer" | "staff";

let counter = 0;
async function addUser(
  role: Role,
  teamId: string | null,
  opts: { email?: string; verified?: boolean } = {},
): Promise<{ id: string; email: string }> {
  counter += 1;
  const email = opts.email ?? `u${counter}@staff.gg`;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${email}, ${`user${counter}`}, ${await hashPassword(PASSWORD)}, ${role}, ${teamId},
      ${opts.verified === false ? null : now}, ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return { id: row.id, email };
}

async function makeTeam(name = "Alpha"): Promise<{ teamId: string; staff: { id: string; email: string } }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values (${name}, ${"P" + name.toUpperCase().slice(0, 5).padEnd(5, "A")}, ${"S" + name.toUpperCase().slice(0, 5).padEnd(5, "A")}, ${deps.now()})
    returning id`;
  const staff = await addUser("staff", t.id);
  await deps.sql`update teams set owner_id = ${staff.id} where id = ${t.id}`;
  return { teamId: t.id, staff };
}

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error("expected the call to fail");
}

async function appLogin(email: string, password: string, ip = "7.7.7.7"): Promise<Response> {
  const req = new Request("http://localhost/v1/auth/login", {
    method: "POST",
    headers: { "content-type": "application/json", "x-forwarded-for": ip },
    body: JSON.stringify({ email, password }),
  });
  return handle(() => loginHandler(req, deps));
}

describe("staff web session", () => {
  it("signInSetsCookieValue", async () => {
    const { staff, teamId } = await makeTeam();
    const token = await staffSignIn(deps, "1.1.1.1", staff.email, PASSWORD);
    expect(token.length).toBeGreaterThan(20);

    const hash = createHash("sha256").update(token).digest("hex");
    const rows = await deps.sql<{ user_id: string; revoked_at: Date | null }[]>`
      select user_id, revoked_at from refresh_tokens where token_hash = ${hash}`;
    expect(rows).toHaveLength(1);
    expect(rows[0].user_id).toBe(staff.id);
    expect(rows[0].revoked_at).toBeNull();

    expect(await staffSession(deps, token)).toEqual({ userId: staff.id, teamId, teamName: "Alpha" });
    // The cookie token is only validated: viewing pages never rotates it.
    expect(await staffSession(deps, token)).not.toBeNull();
    const [count] = await deps.sql<{ n: number }[]>`select count(*)::int as n from refresh_tokens`;
    expect(count.n).toBe(1);
  });

  it("cookieFlagsMatchGlobalConstraints", () => {
    const prod = staffCookieOptions("production");
    expect(STAFF_COOKIE).toBe("pulse_staff");
    expect(prod).toEqual({
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      path: "/",
      maxAge: 30 * 24 * 60 * 60,
    });
    expect(staffCookieOptions("development").secure).toBe(false);
    expect(staffCookieOptions("test").secure).toBe(true);
    expect(staffCookieOptions(undefined).secure).toBe(true);
  });

  it("wrongPasswordSameErrorAsApp", async () => {
    const { staff } = await makeTeam();
    const app = await (await appLogin(staff.email, "wrong-pass1")).json();
    const unknownApp = await (await appLogin("ghost@staff.gg", PASSWORD)).json();

    const wrong = await failure(staffSignIn(deps, "2.2.2.2", staff.email, "wrong-pass1"));
    const unknown = await failure(staffSignIn(deps, "2.2.2.2", "ghost@staff.gg", PASSWORD));
    const demo = await failure(staffSignIn(deps, "2.2.2.2", "staff@pulse.app", PASSWORD));
    for (const e of [wrong, unknown, demo]) {
      expect(e.status).toBe(401);
      expect(e.code).toBe(app.error.code);
      expect(e.publicMessage).toBe(app.error.message);
    }
    expect(unknownApp.error).toEqual(app.error);
    const [count] = await deps.sql<{ n: number }[]>`select count(*)::int as n from refresh_tokens`;
    expect(count.n).toBe(0);
  });

  it("unverifiedEmailSendsCodeAndIsRefused", async () => {
    const { teamId } = await makeTeam();
    const pending = await addUser("staff", teamId, { verified: false });
    const e = await failure(staffSignIn(deps, "3.3.3.3", pending.email, PASSWORD));
    expect(e.code).toBe("email_not_verified");
    expect(e.status).toBe(403);
    expect(deps.mailer.sent).toHaveLength(1);
    expect(deps.mailer.sent[0].subject).toBe("Код подтверждения Pulse");
  });

  it("nonStaffGetsNoContext", async () => {
    const { teamId } = await makeTeam();
    const solo = await addUser("soloPlayer", null);
    const player = await addUser("teamPlayer", teamId);
    const orphan = await addUser("staff", null);

    for (const user of [solo, player, orphan]) {
      const token = await staffSignIn(deps, "4.4.4.4", user.email, PASSWORD);
      expect(await staffSession(deps, token)).toBeNull();
      expect(await resolveSession(deps, token)).toEqual({ kind: "notStaff" });
    }
  });

  it("resolveSessionTellsStaffFromNobody", async () => {
    const { staff, teamId } = await makeTeam();
    const token = await staffSignIn(deps, "4.4.4.5", staff.email, PASSWORD);
    expect(await resolveSession(deps, token)).toEqual({
      kind: "staff",
      ctx: { userId: staff.id, teamId, teamName: "Alpha" },
    });
    expect(await resolveSession(deps, undefined)).toEqual({ kind: "none" });
    expect(await resolveSession(deps, "")).toEqual({ kind: "none" });
    expect(await resolveSession(deps, "not-a-token")).toEqual({ kind: "none" });
  });

  it("revokedSessionRedirects", async () => {
    const { staff } = await makeTeam();

    const revoked = await staffSignIn(deps, "5.5.5.5", staff.email, PASSWORD);
    await deps.sql`update refresh_tokens set revoked_at = ${deps.now()}`;
    expect(await staffSession(deps, revoked)).toBeNull();
    expect(await resolveSession(deps, revoked)).toEqual({ kind: "none" });

    const expired = await staffSignIn(deps, "5.5.5.5", staff.email, PASSWORD);
    expect(await staffSession(deps, expired)).not.toBeNull();
    deps.advance(30 * DAY + 1_000);
    expect(await staffSession(deps, expired)).toBeNull();
  });

  it("deletedUserHasNoSession", async () => {
    const { staff } = await makeTeam();
    const token = await staffSignIn(deps, "6.6.6.6", staff.email, PASSWORD);
    await deps.sql`update teams set owner_id = null`;
    await deps.sql`delete from users where id = ${staff.id}`;
    expect(await staffSession(deps, token)).toBeNull();
  });

  it("signOutRevokes", async () => {
    const { staff } = await makeTeam();
    const token = await staffSignIn(deps, "7.7.7.8", staff.email, PASSWORD);
    const other = await staffSignIn(deps, "7.7.7.8", staff.email, PASSWORD);

    await staffSignOut(deps, token);
    expect(await staffSession(deps, token)).toBeNull();
    const hash = createHash("sha256").update(token).digest("hex");
    const [row] = await deps.sql<{ revoked_at: Date | null }[]>`
      select revoked_at from refresh_tokens where token_hash = ${hash}`;
    expect(row.revoked_at).not.toBeNull();
    // Another browser stays signed in; signing out twice or with junk is harmless.
    expect(await staffSession(deps, other)).not.toBeNull();
    await staffSignOut(deps, token);
    await staffSignOut(deps, undefined);
  });

  it("signingInAgainRevokesThePreviousCookie", async () => {
    const { staff } = await makeTeam();
    const { signInAction } = await import("../app/staff/actions");
    const form = new FormData();
    form.set("email", staff.email);
    form.set("password", PASSWORD);
    const signIn = async () => {
      try {
        await signInAction(IDLE, form);
      } catch (e) {
        if (!String((e as { digest?: unknown }).digest).startsWith("NEXT_REDIRECT")) throw e;
      }
    };

    await signIn(); // first browser visit: no cookie yet
    const first = jar.set[0];
    expect(await staffSession(deps, first)).not.toBeNull();

    jar.cookie = first; // the same browser signs in again with the old cookie attached
    await signIn();
    const second = jar.set[1];
    expect(second).not.toBe(first);
    const hash = createHash("sha256").update(first).digest("hex");
    const [row] = await deps.sql<{ revoked_at: Date | null }[]>`
      select revoked_at from refresh_tokens where token_hash = ${hash}`;
    expect(row.revoked_at).not.toBeNull();
    expect(await staffSession(deps, first)).toBeNull();
    expect(await staffSession(deps, second)).not.toBeNull();
  });

  it("signInRateLimited", async () => {
    const { staff } = await makeTeam();
    for (let i = 0; i < 10; i++) {
      await staffSignIn(deps, "9.9.9.9", staff.email, PASSWORD);
    }
    const e = await failure(staffSignIn(deps, "9.9.9.9", staff.email, PASSWORD));
    expect(e.code).toBe("rate_limited");
    expect(e.status).toBe(429);
    await staffSignIn(deps, "8.8.8.8", staff.email, PASSWORD);
  });

  it("signInSharesTheAppLimit", async () => {
    const { staff } = await makeTeam();
    for (let i = 0; i < 10; i++) expect((await appLogin(staff.email, PASSWORD, "6.6.6.7")).status).toBe(200);
    const e = await failure(staffSignIn(deps, "6.6.6.7", staff.email, PASSWORD));
    expect(e.code).toBe("rate_limited");
  });
});

describe("staff web password reset", () => {
  it("resetSignsInWithNewPassword", async () => {
    const { staff } = await makeTeam();
    await staffResetRequest(deps, "1.2.3.4", ` ${staff.email.toUpperCase()} `);
    expect(deps.mailer.sent).toHaveLength(1);
    expect(deps.mailer.sent[0].subject).toBe("Сброс пароля Pulse");
    const code = deps.mailer.lastCode(staff.email)!;

    const old = await staffSignIn(deps, "1.2.3.4", staff.email, PASSWORD);
    const token = await staffResetConfirm(deps, "1.2.3.4", staff.email, code, "brand-new-pass1");
    expect(await staffSession(deps, token)).toMatchObject({ userId: staff.id });
    // Every earlier session is revoked, like in the app.
    expect(await staffSession(deps, old)).toBeNull();
    expect(await staffSignIn(deps, "1.2.3.4", staff.email, "brand-new-pass1")).toBeTruthy();
    expect((await failure(staffSignIn(deps, "1.2.3.4", staff.email, PASSWORD))).code).toBe("invalid_credentials");
  });

  it("unknownEmailLooksLikeAnyOther", async () => {
    await staffResetRequest(deps, "1.2.3.5", "ghost@staff.gg");
    await staffResetRequest(deps, "1.2.3.5", "staff@pulse.app");
    expect(deps.mailer.sent).toHaveLength(0);
    const e = await failure(staffResetConfirm(deps, "1.2.3.5", "ghost@staff.gg", "123456", "brand-new-pass1"));
    expect(e.code).toBe("invalid_code");
  });

  it("weakPasswordKeepsTheCode", async () => {
    const { staff } = await makeTeam();
    await staffResetRequest(deps, "1.2.3.6", staff.email);
    const code = deps.mailer.lastCode(staff.email)!;
    const weak = await failure(staffResetConfirm(deps, "1.2.3.6", staff.email, code, "short"));
    expect(weak.code).toBe("weak_password");
    expect(await staffResetConfirm(deps, "1.2.3.6", staff.email, code, "brand-new-pass1")).toBeTruthy();
  });

  it("resetSharesTheAuthLimit", async () => {
    for (let i = 0; i < 10; i++) await staffResetRequest(deps, "1.2.3.7", "ghost@staff.gg");
    const e = await failure(staffResetRequest(deps, "1.2.3.7", "ghost@staff.gg"));
    expect(e.code).toBe("rate_limited");
  });
});
