import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { handle } from "../lib/http";
import { loginHandler } from "../lib/auth/login";
import { logoutHandler, refreshHandler } from "../lib/auth/session";
import { passwordResetConfirmHandler, passwordResetHandler } from "../lib/auth/reset";
import { registerHandler } from "../lib/auth/register";
import { verifyEmailHandler } from "../lib/auth/verify";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const EMAIL = "nova@team.gg";
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

function request(path: string, body: unknown, ip?: string): Request {
  return new Request(`http://localhost/v1/auth/${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(ip ? { "x-forwarded-for": ip } : {}) },
    body: JSON.stringify(body),
  });
}

async function call(
  fn: (req: Request, d: typeof deps) => Promise<Response>,
  path: string,
  body: unknown,
  ip?: string,
): Promise<Reply> {
  const res = await handle(() => fn(request(path, body, ip), deps));
  return { status: res.status, body: await res.json() };
}

const login = (email: string, password: string, ip?: string) =>
  call(loginHandler, "login", { email, password }, ip);
const refresh = (refresh_token: string, ip?: string) => call(refreshHandler, "refresh", { refresh_token }, ip);
const logout = (refresh_token: unknown) => call(logoutHandler, "logout", { refresh_token });
const requestReset = (email: string) => call(passwordResetHandler, "password-reset", { email });
const confirmReset = (email: string, code: string, new_password: string) =>
  call(passwordResetConfirmHandler, "password-reset/confirm", { email, code, new_password });

async function signUp(verified = true, email = EMAIL) {
  const reg = await call(registerHandler, "register", {
    nickname: "Nova",
    email,
    password: PASSWORD,
    role: "soloPlayer",
    accepted_terms: true,
    accepted_privacy: true,
    accepted_beta_terms: true,
    beta_terms_version: "2026-10-26",
  });
  expect(reg.status).toBe(201);
  if (!verified) return null;
  const res = await call(verifyEmailHandler, "verify-email", { email, code: deps.mailer.lastCode(email)! });
  expect(res.status).toBe(200);
  deps.mailer.sent.length = 0;
  return res.body.data;
}

describe("login", () => {
  it("loginReturnsSession", async () => {
    await signUp();
    const res = await login("  Nova@Team.GG ", PASSWORD);
    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.data.user).toMatchObject({ email: EMAIL, nickname: "Nova", role: "soloPlayer" });
    expect(res.body.data.user).not.toHaveProperty("password_hash");
    expect(typeof res.body.data.access_token).toBe("string");
    expect(typeof res.body.data.refresh_token).toBe("string");
  });

  it("wrongPasswordAndUnknownEmailLookTheSame", async () => {
    await signUp();
    const wrong = await login(EMAIL, "wrong-pass1");
    const unknown = await login("ghost@team.gg", PASSWORD);
    const demo = await login("player@pulse.app", PASSWORD);
    for (const res of [wrong, unknown, demo]) {
      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe("invalid_credentials");
    }
    expect(unknown.body).toEqual(wrong.body);
    expect(demo.body).toEqual(wrong.body);
  });

  it("unverifiedLoginSendsCode", async () => {
    await signUp(false);
    deps.mailer.sent.length = 0;
    const res = await login(EMAIL, PASSWORD);
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe("email_not_verified");
    expect(deps.mailer.sent).toHaveLength(1);
    expect(deps.mailer.sent[0].subject).toBe("Код подтверждения Pulse");
  });

  it("unverifiedWrongPasswordSendsNothing", async () => {
    await signUp(false);
    deps.mailer.sent.length = 0;
    const res = await login(EMAIL, "wrong-pass1");
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe("invalid_credentials");
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("unverifiedLoginStaysEmailNotVerifiedWhenCodesAreRateLimited", async () => {
    await signUp(false); // 1 code
    for (let i = 0; i < 4; i++) {
      const res = await login(EMAIL, PASSWORD, `10.0.0.${i}`); // codes 2..5
      expect(res.body.error.code).toBe("email_not_verified");
    }
    const res = await login(EMAIL, PASSWORD, "10.0.0.9"); // 6th code is over the limit
    expect(res.status).toBe(403);
    expect(res.body.error.code).toBe("email_not_verified");
    expect(deps.mailer.sent).toHaveLength(5);
  });

  it("authEndpointsAreRateLimited", async () => {
    await signUp();
    for (let i = 0; i < 10; i++) {
      expect((await login(EMAIL, PASSWORD, "9.9.9.9")).status).toBe(200);
    }
    const res = await login(EMAIL, PASSWORD, "9.9.9.9");
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe("rate_limited");
    expect((await login(EMAIL, PASSWORD, "8.8.8.8")).status).toBe(200);
  });

  it("everySessionEndpointIsRateLimited", async () => {
    const endpoints: Array<[typeof loginHandler, string, unknown]> = [
      [logoutHandler, "logout", { refresh_token: "x" }],
      [passwordResetHandler, "password-reset", { email: "a@b.cc" }],
      [passwordResetConfirmHandler, "password-reset/confirm", { email: "a@b.cc", code: "1", new_password: "secret123" }],
    ];
    for (const [fn, path, body] of endpoints) {
      const ip = `7.7.7.${path.length}`;
      for (let i = 0; i < 10; i++) await call(fn, path, body, ip);
      const res = await call(fn, path, body, ip);
      expect(res.status, path).toBe(429);
    }
  });
});

describe("refresh and logout", () => {
  it("refreshHasItsOwnRateLimitOf60PerMinute", async () => {
    const session = await signUp();
    // The sign-in bucket of this IP is used up: refreshing still works.
    for (let i = 0; i < 10; i++) await login(EMAIL, "wrong-pass-1", "6.6.6.6");
    expect((await login(EMAIL, PASSWORD, "6.6.6.6")).status).toBe(429);
    let token = session.refresh_token;
    for (let i = 0; i < 60; i++) {
      const res = await refresh(token, "6.6.6.6");
      expect(res.status, `refresh ${i + 1}`).toBe(200);
      token = res.body.data.refresh_token;
    }
    const over = await refresh(token, "6.6.6.6");
    expect(over.status).toBe(429);
    expect(over.body.error.code).toBe("rate_limited");
    expect((await refresh(token, "6.6.6.7")).status).toBe(200);
  });

  it("refreshAndLogout", async () => {
    const session = await signUp();
    const refreshed = await refresh(session.refresh_token);
    expect(refreshed.status).toBe(200);
    expect(Object.keys(refreshed.body.data).sort()).toEqual(["access_token", "refresh_token"]);
    expect(refreshed.body.data.refresh_token).not.toBe(session.refresh_token);

    const out = await logout(refreshed.body.data.refresh_token);
    expect(out.status).toBe(200);
    expect(out.body).toEqual({ success: true, data: {}, error: null });
    expect((await refresh(refreshed.body.data.refresh_token)).status).toBe(401);
  });

  it("logoutIsSilentForUnknownTokensButNeedsTheField", async () => {
    expect((await logout("not-a-token")).status).toBe(200);
    const missing = await call(logoutHandler, "logout", {});
    expect(missing.status).toBe(422);
    expect(missing.body.error.code).toBe("validation_failed");
  });

  it("refreshRejectsUnknownToken", async () => {
    const res = await refresh("nope");
    expect(res.status).toBe(401);
    expect(res.body.error.code).toBe("unauthorized");
  });
});

describe("password reset", () => {
  it("resetChangesPasswordAndKillsSessions", async () => {
    const session = await signUp();
    expect((await requestReset(EMAIL)).body).toEqual({ success: true, data: {}, error: null });
    expect(deps.mailer.sent).toHaveLength(1);
    expect(deps.mailer.sent[0].subject).toBe("Сброс пароля Pulse");

    const res = await confirmReset(EMAIL, deps.mailer.lastCode(EMAIL)!, "brand-new-pass9");
    expect(res.status).toBe(200);
    expect(res.body.data.user.email).toBe(EMAIL);
    expect(typeof res.body.data.access_token).toBe("string");
    expect(typeof res.body.data.refresh_token).toBe("string");

    expect((await login(EMAIL, PASSWORD)).status).toBe(401);
    expect((await login(EMAIL, "brand-new-pass9")).status).toBe(200);
    expect((await refresh(session.refresh_token)).status).toBe(401);
    expect((await refresh(res.body.data.refresh_token)).status).toBe(200);
  });

  it("resetVerifiesAnUnverifiedAccount", async () => {
    await signUp(false);
    await requestReset(EMAIL);
    const res = await confirmReset(EMAIL, deps.mailer.lastCode(EMAIL)!, "brand-new-pass9");
    expect(res.status).toBe(200);
    expect((await login(EMAIL, "brand-new-pass9")).status).toBe(200);
  });

  it("resetForUnknownEmailIsSilent", async () => {
    for (const email of ["ghost@team.gg", "staff@pulse.app"]) {
      const res = await requestReset(email);
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ success: true, data: {}, error: null });
    }
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("resetRequestSwallowsCodeRateLimit", async () => {
    await signUp(); // verify code is 1 of 5
    for (let i = 0; i < 4; i++) expect((await requestReset(EMAIL)).status).toBe(200);
    const res = await requestReset(EMAIL);
    expect(res.status).toBe(200);
    expect(res.body.data).toEqual({});
  });

  it("confirmForUnknownEmailIsInvalidCode", async () => {
    const res = await confirmReset("ghost@team.gg", "123456", "brand-new-pass9");
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("invalid_code");
  });

  it("weakNewPasswordDoesNotBurnTheCode", async () => {
    await signUp();
    await requestReset(EMAIL);
    const code = deps.mailer.lastCode(EMAIL)!;
    const weak = await confirmReset(EMAIL, code, "short1");
    expect(weak.status).toBe(422);
    expect(weak.body.error.code).toBe("weak_password");
    expect((await confirmReset(EMAIL, code, "brand-new-pass9")).status).toBe(200);
  });

  it("wrongCodeIsRejectedAndPasswordStays", async () => {
    await signUp();
    await requestReset(EMAIL);
    const wrong = deps.mailer.lastCode(EMAIL) === "000000" ? "000001" : "000000";
    const res = await confirmReset(EMAIL, wrong, "brand-new-pass9");
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("invalid_code");
    expect((await login(EMAIL, PASSWORD)).status).toBe(200);
  });
});
