import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { handle } from "../lib/http";
import { registerHandler } from "../lib/auth/register";
import { resendCodeHandler, verifyEmailHandler } from "../lib/auth/verify";
import { resetDb, testDeps } from "./helpers";
import type { Mailer } from "../lib/mail";

const deps = testDeps();
const EMAIL = "nova@team.gg";
const INVITE = /^[A-HJ-NP-Z2-9]{6}$/;

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

async function reply(res: Response): Promise<Reply> {
  return { status: res.status, body: await res.json() };
}

function validBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    nickname: "Nova",
    email: EMAIL,
    password: "secret123",
    role: "soloPlayer",
    accepted_terms: true,
    accepted_privacy: true,
    accepted_beta_terms: true,
    beta_terms_version: "2026-10-26",
    ...overrides,
  };
}

const register = async (overrides: Record<string, unknown> = {}, ip?: string) =>
  reply(await handle(() => registerHandler(request("register", validBody(overrides), ip), deps)));
const verify = async (email: string, code: string, password = "secret123") =>
  reply(await handle(() => verifyEmailHandler(request("verify-email", { email, code, password }), deps)));
const resend = async (email: string) =>
  reply(await handle(() => resendCodeHandler(request("resend-code", { email }), deps)));

async function registerAndVerify(overrides: Record<string, unknown> = {}) {
  const email = String(overrides.email ?? EMAIL).trim().toLowerCase();
  const res = await register(overrides);
  expect(res.status).toBe(201);
  const verified = await verify(email, deps.mailer.lastCode(email)!);
  expect(verified.status).toBe(200);
  return verified.body.data;
}

describe("register", () => {
  it("soloRegistersVerifiesAndGetsSession", async () => {
    const res = await register();
    expect(res.status).toBe(201);
    expect(res.body).toEqual({ success: true, data: { pending_verification: true, email: EMAIL }, error: null });
    expect(deps.mailer.sent).toHaveLength(1);

    const verified = await verify(EMAIL, deps.mailer.lastCode(EMAIL)!);
    expect(verified.status).toBe(200);
    const data = verified.body.data;
    expect(typeof data.access_token).toBe("string");
    expect(typeof data.refresh_token).toBe("string");
    expect(data.user).toEqual({
      id: expect.stringMatching(/^[0-9a-f-]{36}$/),
      nickname: "Nova",
      email: EMAIL,
      role: "soloPlayer",
      tariff: "free",
      team: null,
      team_id: null,
      invite_code: null,
      staff_code: null,
      streak_days: 0,
      faceit_url: null,
    });
    const [row] = await deps.sql`select email_verified_at, password_hash, beta_terms_version from users`;
    expect(row.email_verified_at).toEqual(deps.now());
    expect(row.password_hash).toMatch(/^\$argon2id\$/);
    expect(row.beta_terms_version).toBe("2026-10-26");
  });

  it("userJsonHasNoInternalColumns", async () => {
    const data = await registerAndVerify();
    expect(Object.keys(data.user).sort()).toEqual(
      ["email", "faceit_url", "id", "invite_code", "nickname", "role", "staff_code", "streak_days", "tariff", "team", "team_id"],
    );
  });

  it("emailIsCaseInsensitive", async () => {
    const res = await register({ email: " Nova@Team.GG " });
    expect(res.status).toBe(201);
    expect(res.body.data.email).toBe(EMAIL);
    const verified = await verify("nova@team.gg", deps.mailer.lastCode(EMAIL)!);
    expect(verified.status).toBe(200);
    expect(verified.body.data.user.email).toBe(EMAIL);
    const again = await register({ email: "NOVA@team.gg" });
    expect(again.status).toBe(409);
  });

  it("verifiedEmailIsTaken", async () => {
    await registerAndVerify();
    const res = await register({ nickname: "Other" });
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe("email_taken");
    expect(res.body.data).toBeNull();
  });

  it("demoEmailIsTaken", async () => {
    for (const email of ["player@pulse.app", "solo@pulse.app", "staff@pulse.app"]) {
      const res = await register({ email });
      expect(res.status).toBe(409);
      expect(res.body.error.code).toBe("email_taken");
    }
    expect(await deps.sql`select 1 from users`).toHaveLength(0);
  });

  it("reRegisterUnverifiedSendsNewCode", async () => {
    await register({ nickname: "First" });
    const oldCode = deps.mailer.lastCode(EMAIL)!;
    const second = await register({ nickname: "Second", password: "another123" });
    expect(second.status).toBe(201);
    expect(deps.mailer.sent).toHaveLength(2);
    const newCode = deps.mailer.lastCode(EMAIL)!;

    const stale = await verify(EMAIL, oldCode);
    if (oldCode !== newCode) {
      expect(stale.status).toBe(422);
      expect(stale.body.error.code).toBe("invalid_code");
    }
    // Confirmed with the password of the registration the code belongs to.
    const verified = await verify(EMAIL, newCode, "another123");
    expect(verified.status).toBe(200);
    expect(verified.body.data.user.nickname).toBe("Second");
    expect(await deps.sql`select id from users`).toHaveLength(1);
  });

  it("reRegisterOverwritesRoleAndPassword", async () => {
    await register({ nickname: "First" });
    const [before] = await deps.sql`select id, password_hash from users`;
    await register({ nickname: "Second", role: "soloPlayer", password: "another123", faceit_url: "https://www.faceit.com/en/players/nova" });
    const [after] = await deps.sql`select id, password_hash, faceit_url from users`;
    expect(after.id).toBe(before.id);
    expect(after.password_hash).not.toBe(before.password_hash);
    expect(after.faceit_url).toBe("https://www.faceit.com/en/players/nova");
  });

  it("staffGetsTeamAndInviteCode", async () => {
    const data = await registerAndVerify({ role: "staff", team_name: "  Team Nova  " });
    expect(data.user.role).toBe("staff");
    expect(data.user.team).toBe("Team Nova");
    expect(data.user.invite_code).toMatch(INVITE);
    expect(data.user.staff_code).toMatch(INVITE);
    expect(data.user.team_id).toMatch(/^[0-9a-f-]{36}$/);
    const [team] = await deps.sql`select name, invite_code, staff_code, owner_id from teams`;
    expect(team.owner_id).toBe(data.user.id);
    expect(team.invite_code).toBe(data.user.invite_code);
    expect(team.staff_code).toBe(data.user.staff_code);
  });

  it("staffRequiresTeamName", async () => {
    for (const team_name of [undefined, null, "", "   ", "x".repeat(61)]) {
      const res = await register({ role: "staff", team_name });
      expect(res.status).toBe(422);
      expect(res.body.error.code).toBe("validation_failed");
    }
    expect(await deps.sql`select 1 from users`).toHaveLength(0);
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("playerJoinsByCodeCaseInsensitive", async () => {
    await deps.sql`insert into teams (name, invite_code, staff_code) values ('Alpha', 'ABC234', 'XYZ789')`;
    const data = await registerAndVerify({ role: "teamPlayer", team_code: " abc234 " });
    expect(data.user.role).toBe("teamPlayer");
    expect(data.user.team).toBe("Alpha");
    expect(data.user.invite_code).toBeNull();
    const [team] = await deps.sql`select id from teams`;
    expect(data.user.team_id).toBe(team.id);
  });

  it("unknownTeamCode404", async () => {
    const res = await register({ role: "teamPlayer", team_code: "ZZZZZZ" });
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe("team_not_found");
    expect(await deps.sql`select 1 from users`).toHaveLength(0);
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("teamPlayerWithoutCodeIsValidationFailed", async () => {
    const res = await register({ role: "teamPlayer" });
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("validation_failed");
  });

  it("teamFieldsAreIgnoredForOtherRoles", async () => {
    const solo = await register({ team_code: "ZZZZZZ", team_name: "Ignored" });
    expect(solo.status).toBe(201);
    const player = await register({ email: "p@team.gg", role: "teamPlayer", team_code: "ZZZZZZ", team_name: "Ignored" });
    expect(player.status).toBe(404);
    expect(await deps.sql`select 1 from teams`).toHaveLength(0);
  });

  it("consentsRequired", async () => {
    for (const field of ["accepted_terms", "accepted_privacy", "accepted_beta_terms"]) {
      const res = await register({ [field]: false });
      expect(res.status).toBe(422);
      expect(res.body.error.code).toBe("validation_failed");
    }
    const missing = await register({ accepted_beta_terms: undefined });
    expect(missing.body.error.code).toBe("validation_failed");
    expect(await deps.sql`select 1 from users`).toHaveLength(0);
  });

  it("weakPasswordAndBadEmail", async () => {
    const weak = await register({ password: "short1" });
    expect(weak.status).toBe(422);
    expect(weak.body.error.code).toBe("weak_password");
    const noDigit = await register({ password: "onlyletters" });
    expect(noDigit.body.error.code).toBe("weak_password");
    const bad = await register({ email: "not-an-email" });
    expect(bad.status).toBe(422);
    expect(bad.body.error.code).toBe("invalid_email");
  });

  it("otherSchemaProblemsAreValidationFailed", async () => {
    const cases: Record<string, unknown>[] = [
      { role: "admin" },
      { nickname: "   " },
      { nickname: "x".repeat(41) },
      { faceit_url: "https://example.com/nova" },
      { password: 12345678 },
    ];
    for (const overrides of cases) {
      const res = await register(overrides);
      expect(res.status, JSON.stringify(overrides)).toBe(422);
      expect(res.body.error.code, JSON.stringify(overrides)).toBe("validation_failed");
    }
  });

  it("unparseableJsonIsBadRequest", async () => {
    const res = await reply(await handle(() => registerHandler(
      new Request("http://localhost/v1/auth/register", { method: "POST", body: "{nope" }), deps)));
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe("bad_request");
  });

  it("faceitUrlNullOrEmptyIsAbsent", async () => {
    for (const faceit_url of [null, "", "  "]) {
      await resetDb(deps.sql);
      const data = await registerAndVerify({ faceit_url });
      expect(data.user.faceit_url).toBeNull();
    }
    await resetDb(deps.sql);
    const data = await registerAndVerify({ faceit_url: " https://www.faceit.com/en/players/nova " });
    expect(data.user.faceit_url).toBe("https://www.faceit.com/en/players/nova");
  });

  it("concurrentRegistrationOfNewEmailNeverFails500", async () => {
    const results = await Promise.all([register({ nickname: "A" }), register({ nickname: "B" })]);
    const statuses = results.map((r) => r.status).sort();
    expect(statuses[0]).toBe(201);
    expect([201, 409]).toContain(statuses[1]);
    expect(await deps.sql`select 1 from users`).toHaveLength(1);
  });

  it("concurrentStaffRegistrationsLeaveOneTeam", async () => {
    await Promise.all([
      register({ role: "staff", team_name: "A" }),
      register({ role: "staff", team_name: "B" }),
    ]);
    expect(await deps.sql`select 1 from teams`).toHaveLength(1);
  });

  it("rateLimitsPerIp", async () => {
    for (let i = 0; i < 10; i++) {
      const res = await register({ email: `u${i}@team.gg` }, "9.9.9.9");
      expect(res.status).toBe(201);
    }
    const limited = await register({ email: "u10@team.gg" }, "9.9.9.9");
    expect(limited.status).toBe(429);
    expect(limited.body.error.code).toBe("rate_limited");
    const other = await register({ email: "u11@team.gg" }, "8.8.8.8");
    expect(other.status).toBe(201);
  });
});

describe("register team handling for an unverified account", () => {
  it("staffReRegisterRenamesTeamAndKeepsInviteCode", async () => {
    await register({ role: "staff", team_name: "Old" });
    const [before] = await deps.sql`select id, invite_code from teams`;
    await register({ role: "staff", team_name: "New" });
    const teams = await deps.sql`select id, name, invite_code from teams`;
    expect(teams).toHaveLength(1);
    expect(teams[0]).toMatchObject({ id: before.id, name: "New", invite_code: before.invite_code });
    const verified = await verify(EMAIL, deps.mailer.lastCode(EMAIL)!);
    expect(verified.body.data.user).toMatchObject({ team: "New", invite_code: before.invite_code, team_id: before.id });
  });

  it("staffBecomingSoloDeletesOwnedTeam", async () => {
    await register({ role: "staff", team_name: "Old" });
    await register({ role: "soloPlayer" });
    expect(await deps.sql`select 1 from teams`).toHaveLength(0);
    const verified = await verify(EMAIL, deps.mailer.lastCode(EMAIL)!);
    expect(verified.body.data.user).toMatchObject({ role: "soloPlayer", team: null, team_id: null, invite_code: null });
  });

  it("staffBecomingPlayerJoinsOtherTeamAndDeletesOwned", async () => {
    await deps.sql`insert into teams (name, invite_code, staff_code) values ('Alpha', 'ABC234', 'XYZ789')`;
    await register({ role: "staff", team_name: "Mine" });
    await register({ role: "teamPlayer", team_code: "abc234" });
    const teams = await deps.sql`select name from teams`;
    expect(teams.map((t) => t.name)).toEqual(["Alpha"]);
    const verified = await verify(EMAIL, deps.mailer.lastCode(EMAIL)!);
    expect(verified.body.data.user).toMatchObject({ role: "teamPlayer", team: "Alpha", invite_code: null });
  });

  it("playerBecomingStaffGetsATeam", async () => {
    await deps.sql`insert into teams (name, invite_code, staff_code) values ('Alpha', 'ABC234', 'XYZ789')`;
    await register({ role: "teamPlayer", team_code: "ABC234" });
    await register({ role: "staff", team_name: "Mine" });
    const verified = await verify(EMAIL, deps.mailer.lastCode(EMAIL)!);
    expect(verified.body.data.user).toMatchObject({ role: "staff", team: "Mine" });
    expect(verified.body.data.user.invite_code).toMatch(INVITE);
  });
});

describe("verify-email", () => {
  it("unknownEmailIsInvalidCode", async () => {
    const res = await verify("ghost@team.gg", "123456");
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("invalid_code");
  });

  it("wrongCodeIsInvalidCodeAndSameShapeAsUnknownEmail", async () => {
    await register();
    const code = deps.mailer.lastCode(EMAIL)!;
    const res = await verify(EMAIL, code === "000000" ? "111111" : "000000");
    const unknown = await verify("ghost@team.gg", "123456");
    expect(res.status).toBe(422);
    expect(res.body).toEqual(unknown.body);
  });

  it("wrongPasswordIsInvalidCodeAndCountsAnAttempt", async () => {
    await register();
    const code = deps.mailer.lastCode(EMAIL)!;
    const res = await verify(EMAIL, code, "other-pass-2");
    const unknown = await verify("ghost@team.gg", "123456");
    expect(res.status).toBe(422);
    expect(res.body).toEqual(unknown.body);
    const [row] = await deps.sql<{ attempts: number; consumed_at: Date | null }[]>`
      select attempts, consumed_at from email_codes order by created_at desc limit 1`;
    expect(row).toEqual({ attempts: 1, consumed_at: null });
    expect((await verify(EMAIL, code)).status).toBe(200);
  });

  it("wrongPasswordAttemptsRunOutLikeWrongCodes", async () => {
    await register();
    const code = deps.mailer.lastCode(EMAIL)!;
    for (let i = 0; i < 4; i++) expect((await verify(EMAIL, code, "other-pass-2")).body.error.code).toBe("invalid_code");
    expect((await verify(EMAIL, code, "other-pass-2")).body.error.code).toBe("too_many_attempts");
    expect((await verify(EMAIL, code)).body.error.code).toBe("too_many_attempts");
  });

  it("reRegistrationByAnotherPasswordCannotBeVerifiedWithTheFirstOne", async () => {
    // Someone re-registers the unverified address with their own password; the owner's code step must not confirm it.
    await register();
    await register({ password: "attacker-pass-9" });
    const code = deps.mailer.lastCode(EMAIL)!;
    expect((await verify(EMAIL, code)).body.error.code).toBe("invalid_code");
    const [user] = await deps.sql<{ email_verified_at: Date | null }[]>`select email_verified_at from users`;
    expect(user.email_verified_at).toBeNull();
  });

  it("verifiedUserWithNoActiveCodeIsInvalidCode", async () => {
    await registerAndVerify();
    const code = deps.mailer.lastCode(EMAIL)!;
    const res = await verify(EMAIL, code);
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("invalid_code");
  });

  it("expiredCode", async () => {
    await register();
    const code = deps.mailer.lastCode(EMAIL)!;
    deps.advance(16 * 60_000);
    const res = await verify(EMAIL, code);
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("code_expired");
  });

  it("bodyProblemsAreValidationFailed", async () => {
    const res = await reply(await handle(() => verifyEmailHandler(request("verify-email", { email: EMAIL }), deps)));
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("validation_failed");
  });

  it("rateLimitsPerIp", async () => {
    for (let i = 0; i < 10; i++) await verify("ghost@team.gg", "123456");
    const res = await verify("ghost@team.gg", "123456");
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe("rate_limited");
  });
});

describe("resend-code", () => {
  it("resendNeverRevealsAccounts", async () => {
    const unknown = await resend("ghost@team.gg");
    expect(unknown).toEqual({ status: 200, body: { success: true, data: {}, error: null } });
    expect(deps.mailer.sent).toHaveLength(0);

    await registerAndVerify();
    deps.mailer.sent.length = 0;
    const verified = await resend(EMAIL);
    expect(verified).toEqual({ status: 200, body: { success: true, data: {}, error: null } });
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("resendSendsNewCodeToUnverifiedUser", async () => {
    await register();
    const first = deps.mailer.lastCode(EMAIL)!;
    const res = await resend(" Nova@Team.GG ");
    expect(res).toEqual({ status: 200, body: { success: true, data: {}, error: null } });
    expect(deps.mailer.sent).toHaveLength(2);
    const second = deps.mailer.lastCode(EMAIL)!;
    if (first !== second) {
      expect((await verify(EMAIL, first)).body.error.code).toBe("invalid_code");
    }
    expect((await verify(EMAIL, second)).status).toBe(200);
  });

  it("resendIsLimitedToFiveCodesPerHour", async () => {
    await register();
    for (let i = 0; i < 4; i++) expect((await resend(EMAIL)).status).toBe(200);
    const res = await resend(EMAIL);
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe("rate_limited");
    expect(deps.mailer.sent).toHaveLength(5);
  });

  it("rateLimitsPerIp", async () => {
    for (let i = 0; i < 10; i++) await resend("ghost@team.gg");
    const res = await resend("ghost@team.gg");
    expect(res.status).toBe(429);
  });
});

describe("when the mail provider fails", () => {
  const failing: Mailer = {
    send: async () => {
      throw new Error("SMTP failed to send mail: boom");
    },
  };

  it("registerReturns503WhenMailFails", async () => {
    const quiet = vi.spyOn(console, "error").mockImplementation(() => {});
    const working = deps.mailer;
    deps.mailer = failing as typeof deps.mailer;
    const first = await register();
    deps.mailer = working;
    expect(first.status).toBe(503);
    expect(first.body.error.code).toBe("mail_unavailable");
    expect(first.body.error.message).toBe("Could not send the email. Try again later.");
    expect(quiet).toHaveBeenCalled();
    quiet.mockRestore();

    const second = await register();
    expect(second.status).toBe(201);
    expect(deps.mailer.lastCode(EMAIL)).toMatch(/^\d{6}$/);
  });
});

describe("wrong staff codes are limited for everyone together", () => {
  const WRONG = "ZZZZZZ";
  let n = 0;
  const staffJoin = (code: string, ip: string) =>
    register({ role: "staff", staff_code: code, email: `helper${n++}@team.gg` }, ip);

  async function teamWithStaffCode(): Promise<string> {
    const owner = await register({ role: "staff", team_name: "Alpha", email: "owner@team.gg" }, "1.1.1.1");
    expect(owner.status).toBe(201);
    const [row] = await deps.sql`select staff_code from teams`;
    return row.staff_code as string;
  }

  it("aCorrectCodeStillWorksBeforeTheLimitAndCorrectCodesAreNotMisses", async () => {
    const code = await teamWithStaffCode();
    for (let i = 0; i < 29; i++) expect((await staffJoin(WRONG, `10.0.0.${i}`)).status).toBe(404);
    const good = await staffJoin(code, "10.1.0.1");
    expect(good.status).toBe(201);
    // the correct join was not counted: one more miss is still a plain team_not_found
    const miss = await staffJoin(WRONG, "10.1.0.2");
    expect(miss.status).toBe(404);
    expect(miss.body.error.code).toBe("team_not_found");
  });

  it("afterThirtyMissesEvenACorrectCodeIsRefusedUntilTheWindowPasses", async () => {
    const code = await teamWithStaffCode();
    // 30 misses from 30 different addresses: no single IP limit is involved
    for (let i = 0; i < 30; i++) {
      const res = await staffJoin(WRONG, `10.0.0.${i}`);
      expect(res.status).toBe(404);
      expect(res.body.error.code).toBe("team_not_found");
    }
    const refused = await staffJoin(code, "10.2.0.1");
    expect(refused.status).toBe(429);
    expect(refused.body.error.code).toBe("rate_limited");
    // a wrong code is refused the same way, so the reply no longer tells right from wrong
    const refusedWrong = await staffJoin(WRONG, "10.2.0.2");
    expect(refusedWrong.status).toBe(429);
    expect(refusedWrong.body.error.code).toBe("rate_limited");

    // other registrations are unaffected
    expect((await register({ email: "solo@team.gg" }, "10.2.0.3")).status).toBe(201);
    expect((await register({ role: "staff", team_name: "Beta", email: "beta@team.gg" }, "10.2.0.4")).status).toBe(201);

    deps.advance(60 * 60 * 1000);
    expect((await staffJoin(code, "10.2.0.5")).status).toBe(201);
  });

  it("missesOnTheSameIpAreAlsoCountedGlobally", async () => {
    const code = await teamWithStaffCode();
    // ten IPs, three misses each (the per-IP limit is ten a minute)
    for (let ip = 0; ip < 10; ip++) {
      for (let k = 0; k < 3; k++) await staffJoin(WRONG, `10.3.0.${ip}`);
    }
    const refused = await staffJoin(code, "10.3.1.1");
    expect(refused.status).toBe(429);
    expect(refused.body.error.code).toBe("rate_limited");
  });
});
