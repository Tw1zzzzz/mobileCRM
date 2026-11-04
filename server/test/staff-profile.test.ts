import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { hashPassword, verifyPassword } from "../lib/auth/passwords";
import { hashRefreshToken, issueSession } from "../lib/auth/tokens";
import { ApiError } from "../lib/http";
import type { StaffContext } from "../lib/staff/access";
import {
  changePassword,
  confirmEmailChange,
  deleteOwnAccount,
  loadProfile,
  requestEmailChange,
  signOutEverywhere,
  updateProfile,
} from "../lib/staff/profile";
import {
  changePasswordCore,
  deleteAccountCore,
  emailChangeCore,
  saveProfileCore,
} from "../lib/staff/profile-forms";
import { staffSession, staffSignIn } from "../lib/staff/session";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const PASSWORD = "secret123";
const MINUTE = 60_000;

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

let counter = 0;

async function addUser(
  role: "soloPlayer" | "teamPlayer" | "staff",
  teamId: string | null,
  email?: string,
): Promise<{ id: string; email: string }> {
  counter += 1;
  const address = email ?? `p${counter}@profile.gg`;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${address}, ${`user${counter}`}, ${await hashPassword(PASSWORD)}, ${role}, ${teamId}, ${now},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return { id: row.id, email: address };
}

async function makeTeam(): Promise<{ ctx: StaffContext; email: string }> {
  const [t] = await deps.sql<{ id: string }[]>`
    insert into teams (name, invite_code, staff_code, created_at)
    values ('Alpha', 'PALPHA', 'SALPHA', ${deps.now()}) returning id`;
  const staff = await addUser("staff", t.id);
  await deps.sql`update teams set owner_id = ${staff.id} where id = ${t.id}`;
  return { ctx: { userId: staff.id, teamId: t.id, teamName: "Alpha" }, email: staff.email };
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

async function activeTokens(userId: string): Promise<number> {
  const [row] = await deps.sql<{ n: number }[]>`
    select count(*)::int as n from refresh_tokens where user_id = ${userId} and revoked_at is null`;
  return row.n;
}

describe("staff profile: main data", () => {
  it("profileUpdatesNickAndFaceit", async () => {
    const { ctx, email } = await makeTeam();
    const saved = await updateProfile(deps, ctx, {
      nickname: "  Coach Nova ",
      faceitUrl: " https://www.faceit.com/ru/players/nova ",
    });
    expect(saved).toEqual({ nickname: "Coach Nova", faceitUrl: "https://www.faceit.com/ru/players/nova" });
    expect(await loadProfile(deps, ctx)).toEqual({
      nickname: "Coach Nova",
      email,
      faceitUrl: "https://www.faceit.com/ru/players/nova",
      pendingEmail: null,
    });

    // An empty link removes it.
    await updateProfile(deps, ctx, { nickname: "Coach Nova", faceitUrl: "  " });
    expect((await loadProfile(deps, ctx)).faceitUrl).toBeNull();
  });

  it("invalidFaceitRefused", async () => {
    const { ctx } = await makeTeam();
    await updateProfile(deps, ctx, { nickname: "Keep", faceitUrl: "https://faceit.com/en/players/keep" });
    for (const faceitUrl of ["https://example.com/players/nova", "not a link", "ftp://faceit.com/x"]) {
      const e = await failure(updateProfile(deps, ctx, { nickname: "Changed", faceitUrl }));
      expect(e.code).toBe("validation_failed");
      expect(e.status).toBe(422);
      expect(e.meta).toEqual({ field: "faceit_url" });
    }
    // Nothing was saved by the refused calls.
    const profile = await loadProfile(deps, ctx);
    expect(profile.nickname).toBe("Keep");
    expect(profile.faceitUrl).toBe("https://faceit.com/en/players/keep");
  });

  it("nicknameMustBeOneToFortyCharacters", async () => {
    const { ctx } = await makeTeam();
    const empty = await failure(updateProfile(deps, ctx, { nickname: "   ", faceitUrl: "" }));
    expect(empty.meta).toEqual({ field: "nickname_empty" });
    const long = await failure(updateProfile(deps, ctx, { nickname: "x".repeat(41), faceitUrl: "" }));
    expect(long.meta).toEqual({ field: "nickname_long" });
    await updateProfile(deps, ctx, { nickname: "x".repeat(40), faceitUrl: "" });
    expect((await loadProfile(deps, ctx)).nickname).toBe("x".repeat(40));
  });
});

describe("staff profile: password", () => {
  it("passwordChangeNeedsCurrent", async () => {
    const { ctx, email } = await makeTeam();
    const e = await failure(changePassword(deps, ctx, "wrong-pass1", "brand-new1", null));
    expect(e.code).toBe("invalid_credentials");
    expect(e.status).toBe(401);
    const [row] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${ctx.userId}`;
    expect(await verifyPassword(row.password_hash, PASSWORD)).toBe(true);

    await changePassword(deps, ctx, PASSWORD, "brand-new1", null);
    const [after] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${ctx.userId}`;
    expect(await verifyPassword(after.password_hash, "brand-new1")).toBe(true);
    expect(await verifyPassword(after.password_hash, PASSWORD)).toBe(false);
    expect(await staffSignIn(deps, "1.1.1.1", email, "brand-new1")).toBeTruthy();
  });

  it("weakNewPasswordRefused", async () => {
    const { ctx } = await makeTeam();
    for (const weak of ["short1", "onlyletters", "12345678"]) {
      const e = await failure(changePassword(deps, ctx, PASSWORD, weak, null));
      expect(e.code).toBe("weak_password");
    }
    const [row] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${ctx.userId}`;
    expect(await verifyPassword(row.password_hash, PASSWORD)).toBe(true);
  });

  it("passwordChangeRevokesOtherSessions", async () => {
    const { ctx } = await makeTeam();
    const here = (await issueSession(deps, ctx.userId)).refresh_token;
    const phone = (await issueSession(deps, ctx.userId)).refresh_token;
    const other = (await issueSession(deps, ctx.userId)).refresh_token;
    expect(await activeTokens(ctx.userId)).toBe(3);

    await changePassword(deps, ctx, PASSWORD, "brand-new1", hashRefreshToken(here));

    expect(await staffSession(deps, here)).not.toBeNull();
    expect(await staffSession(deps, phone)).toBeNull();
    expect(await staffSession(deps, other)).toBeNull();
    expect(await activeTokens(ctx.userId)).toBe(1);
  });

  it("passwordChangeWithoutKeptSessionRevokesAll", async () => {
    const { ctx } = await makeTeam();
    await issueSession(deps, ctx.userId);
    await changePassword(deps, ctx, PASSWORD, "brand-new1", null);
    expect(await activeTokens(ctx.userId)).toBe(0);
  });

  it("aFailedRevokeLeavesThePasswordUnchanged", async () => {
    const { ctx } = await makeTeam();
    await issueSession(deps, ctx.userId);
    const [before] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${ctx.userId}`;
    await deps.sql`
      create or replace function profile_test_block_revoke() returns trigger language plpgsql as
      $$ begin raise exception 'revoke blocked'; end $$`;
    await deps.sql`
      create trigger profile_test_block_revoke before update on refresh_tokens
      for each row execute function profile_test_block_revoke()`;
    try {
      await expect(changePassword(deps, ctx, PASSWORD, "brand-new1", null)).rejects.toThrow();
    } finally {
      await deps.sql`drop trigger profile_test_block_revoke on refresh_tokens`;
      await deps.sql`drop function profile_test_block_revoke()`;
    }
    const [after] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${ctx.userId}`;
    expect(after.password_hash).toBe(before.password_hash);
    expect(await verifyPassword(after.password_hash, PASSWORD)).toBe(true);
    expect(await activeTokens(ctx.userId)).toBe(1);
  });

  it("wrongCurrentPasswordIsRateLimited", async () => {
    const { ctx } = await makeTeam();
    for (let i = 0; i < 10; i++) await failure(changePassword(deps, ctx, "wrong-pass1", "brand-new1", null));
    const e = await failure(changePassword(deps, ctx, PASSWORD, "brand-new1", null));
    expect(e.code).toBe("rate_limited");
  });
});

describe("staff profile: email", () => {
  it("emailChangeSendsCodeToNewAddress", async () => {
    const { ctx, email } = await makeTeam();
    await requestEmailChange(deps, ctx, "  New.Address@Mail.GG ", PASSWORD);

    expect(deps.mailer.sent).toHaveLength(1);
    expect(deps.mailer.sent[0].to).toBe("new.address@mail.gg");
    expect(deps.mailer.sent.some((m) => m.to === email)).toBe(false);
    const code = deps.mailer.lastCode("new.address@mail.gg")!;
    expect(code).toMatch(/^\d{6}$/);

    // Nothing changes until the code is confirmed.
    const pending = await loadProfile(deps, ctx);
    expect(pending.email).toBe(email);
    expect(pending.pendingEmail).toBe("new.address@mail.gg");

    await confirmEmailChange(deps, ctx, ` ${code} `);
    const done = await loadProfile(deps, ctx);
    expect(done.email).toBe("new.address@mail.gg");
    expect(done.pendingEmail).toBeNull();
    // The sessions stay, and the new address signs in.
    expect(await staffSignIn(deps, "2.2.2.2", "new.address@mail.gg", PASSWORD)).toBeTruthy();
  });

  it("codeIsGoodFor15MinutesAndOnlyOnce", async () => {
    const { ctx } = await makeTeam();
    await requestEmailChange(deps, ctx, "late@mail.gg", PASSWORD);
    const code = deps.mailer.lastCode("late@mail.gg")!;
    deps.advance(15 * MINUTE + 1);
    expect((await failure(confirmEmailChange(deps, ctx, code))).code).toBe("code_expired");

    await requestEmailChange(deps, ctx, "late@mail.gg", PASSWORD);
    const fresh = deps.mailer.lastCode("late@mail.gg")!;
    await confirmEmailChange(deps, ctx, fresh);
    expect((await failure(confirmEmailChange(deps, ctx, fresh))).code).toBe("invalid_code");
  });

  it("emailChangeNeedsPassword", async () => {
    const { ctx } = await makeTeam();
    for (const password of ["wrong-pass1", ""]) {
      const e = await failure(requestEmailChange(deps, ctx, "needs@mail.gg", password));
      expect(e.code).toBe("invalid_credentials");
      expect(e.status).toBe(401);
    }
    expect(deps.mailer.sent).toHaveLength(0);
    expect((await loadProfile(deps, ctx)).pendingEmail).toBeNull();
    // Wrong tries count under the same limit as the password change: ten in all, then rate_limited.
    for (let i = 0; i < 8; i++) await failure(requestEmailChange(deps, ctx, "needs@mail.gg", "wrong-pass1"));
    expect((await failure(requestEmailChange(deps, ctx, "needs@mail.gg", PASSWORD))).code).toBe("rate_limited");
    expect((await failure(changePassword(deps, ctx, PASSWORD, "brand-new1", null))).code).toBe("rate_limited");

    await resetDb(deps.sql);
    const fresh = await makeTeam();
    await requestEmailChange(deps, fresh.ctx, "needs@mail.gg", PASSWORD);
    expect(deps.mailer.lastCode("needs@mail.gg")).toMatch(/^\d{6}$/);
  });

  it("invalidAddressRefused", async () => {
    const { ctx } = await makeTeam();
    const e = await failure(requestEmailChange(deps, ctx, "not-an-email", PASSWORD));
    expect(e.code).toBe("invalid_email");
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("probingTakenAddressesIsCappedByTheEmailRequestLimit", async () => {
    const { ctx } = await makeTeam();
    const other = await addUser("soloPlayer", null);
    // Five requests are allowed per hour; probing a registered address counts against them like any other.
    for (let i = 0; i < 5; i++) expect((await failure(requestEmailChange(deps, ctx, other.email, PASSWORD))).code).toBe("email_taken");
    const e = await failure(requestEmailChange(deps, ctx, other.email, PASSWORD));
    expect(e.code).toBe("rate_limited");
    expect(e.message).not.toMatch(/registered/i);
    expect((await failure(requestEmailChange(deps, ctx, "staff@pulse.app", PASSWORD))).code).toBe("rate_limited");
    expect(deps.mailer.sent).toHaveLength(0);
  });

  it("emailChangeToTakenAddressRefused", async () => {
    const { ctx, email } = await makeTeam();
    const other = await addUser("soloPlayer", null);
    for (const taken of [other.email, other.email.toUpperCase(), email, "staff@pulse.app"]) {
      const e = await failure(requestEmailChange(deps, ctx, taken, PASSWORD));
      expect(e.code).toBe("email_taken");
      expect(e.status).toBe(409);
    }
    expect(deps.mailer.sent).toHaveLength(0);
    expect((await loadProfile(deps, ctx)).pendingEmail).toBeNull();
  });

  it("addressTakenMeanwhileIsRefusedAtConfirmation", async () => {
    const { ctx, email } = await makeTeam();
    await requestEmailChange(deps, ctx, "race@mail.gg", PASSWORD);
    const code = deps.mailer.lastCode("race@mail.gg")!;
    await addUser("soloPlayer", null, "race@mail.gg");
    expect((await failure(confirmEmailChange(deps, ctx, code))).code).toBe("email_taken");
    expect((await loadProfile(deps, ctx)).email).toBe(email);
  });

  it("emailChangeWrongCodeCountsAttempts", async () => {
    const { ctx, email } = await makeTeam();
    await requestEmailChange(deps, ctx, "guess@mail.gg", PASSWORD);
    const code = deps.mailer.lastCode("guess@mail.gg")!;
    const wrong = code === "000000" ? "111111" : "000000";

    expect((await failure(confirmEmailChange(deps, ctx, wrong))).code).toBe("invalid_code");
    const [first] = await deps.sql<{ attempts: number }[]>`
      select attempts from email_codes where user_id = ${ctx.userId} and purpose = 'email_change'`;
    expect(first.attempts).toBe(1);

    for (let i = 0; i < 3; i++) await failure(confirmEmailChange(deps, ctx, wrong));
    expect((await failure(confirmEmailChange(deps, ctx, wrong))).code).toBe("too_many_attempts");
    // After the fifth miss even the right code is refused.
    expect((await failure(confirmEmailChange(deps, ctx, code))).code).toBe("too_many_attempts");
    const profile = await loadProfile(deps, ctx);
    expect(profile.email).toBe(email);
    expect(profile.pendingEmail).toBeNull();
  });

  it("newRequestVoidsTheOlderCode", async () => {
    const { ctx } = await makeTeam();
    await requestEmailChange(deps, ctx, "first@mail.gg", PASSWORD);
    const first = deps.mailer.lastCode("first@mail.gg")!;
    await requestEmailChange(deps, ctx, "second@mail.gg", PASSWORD);
    const second = deps.mailer.lastCode("second@mail.gg")!;
    expect((await loadProfile(deps, ctx)).pendingEmail).toBe("second@mail.gg");
    if (first !== second) await failure(confirmEmailChange(deps, ctx, first));
    await confirmEmailChange(deps, ctx, second);
    expect((await loadProfile(deps, ctx)).email).toBe("second@mail.gg");
  });

  it("codeOfAnotherPurposeDoesNotChangeTheEmail", async () => {
    const { ctx, email } = await makeTeam();
    // A password-reset code is not an email-change code, even with the right digits.
    const { sendCode } = await import("../lib/auth/codes");
    await sendCode(deps, { id: ctx.userId, email }, "reset");
    const code = deps.mailer.lastCode(email)!;
    expect((await failure(confirmEmailChange(deps, ctx, code))).code).toBe("invalid_code");
  });
});

describe("staff profile: sessions and deletion", () => {
  it("signOutEverywhereRevokesAll", async () => {
    const { ctx } = await makeTeam();
    const web = (await issueSession(deps, ctx.userId)).refresh_token;
    const app = (await issueSession(deps, ctx.userId)).refresh_token;
    const other = await addUser("soloPlayer", null);
    const othersToken = (await issueSession(deps, other.id)).refresh_token;

    await signOutEverywhere(deps, ctx);

    expect(await staffSession(deps, web)).toBeNull();
    expect(await staffSession(deps, app)).toBeNull();
    expect(await activeTokens(ctx.userId)).toBe(0);
    // Another person's sessions are untouched.
    expect(await activeTokens(other.id)).toBe(1);
    expect(othersToken).toBeTruthy();
  });

  it("deleteNeedsPassword", async () => {
    const { ctx } = await makeTeam();
    const e = await failure(deleteOwnAccount(deps, ctx, "wrong-pass1"));
    expect(e.code).toBe("invalid_credentials");
    const [row] = await deps.sql<{ n: number }[]>`select count(*)::int as n from users where id = ${ctx.userId}`;
    expect(row.n).toBe(1);
    expect((await failure(deleteOwnAccount(deps, ctx, ""))).code).toBe("invalid_credentials");
  });

  it("deleteRemovesAccount", async () => {
    const { ctx } = await makeTeam();
    const token = (await issueSession(deps, ctx.userId)).refresh_token;
    await deleteOwnAccount(deps, ctx, PASSWORD);

    const [users] = await deps.sql<{ n: number }[]>`select count(*)::int as n from users where id = ${ctx.userId}`;
    expect(users.n).toBe(0);
    const [tokens] = await deps.sql<{ n: number }[]>`select count(*)::int as n from refresh_tokens`;
    expect(tokens.n).toBe(0);
    expect(await staffSession(deps, token)).toBeNull();
    // The team had no one else: it goes with its owner.
    const [teams] = await deps.sql<{ n: number }[]>`select count(*)::int as n from teams`;
    expect(teams.n).toBe(0);
  });

  it("deleteHandsTheTeamToAnotherStaffMember", async () => {
    const { ctx } = await makeTeam();
    const helper = await addUser("staff", ctx.teamId);
    const player = await addUser("teamPlayer", ctx.teamId);
    await deleteOwnAccount(deps, ctx, PASSWORD);

    const [team] = await deps.sql<{ owner_id: string }[]>`select owner_id from teams where id = ${ctx.teamId}`;
    expect(team.owner_id).toBe(helper.id);
    const [kept] = await deps.sql<{ team_id: string | null }[]>`select team_id from users where id = ${player.id}`;
    expect(kept.team_id).toBe(ctx.teamId);
  });
});

function formOf(values: Record<string, string>): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(values)) form.set(key, value);
  return form;
}

describe("staff profile: forms say it in the app's words", () => {
  it("saveShowsTheNoticeOrTheAppText", async () => {
    const { ctx } = await makeTeam();
    expect(await saveProfileCore(deps, ctx, formOf({ nickname: "Nova", faceitUrl: "" }))).toEqual({
      status: "done",
      message: "Сохранено",
    });
    const bad = (values: Record<string, string>) => saveProfileCore(deps, ctx, formOf(values));
    expect(await bad({ nickname: "Nova", faceitUrl: "https://example.com/x" })).toEqual({
      status: "error",
      message: "Укажите корректную ссылку на профиль Faceit",
    });
    expect(await bad({ nickname: "", faceitUrl: "" })).toEqual({
      status: "error",
      message: "Заполните обязательные поля",
    });
    expect(await bad({ nickname: "x".repeat(41), faceitUrl: "" })).toEqual({
      status: "error",
      message: "Не больше 40 символов",
    });
  });

  it("passwordFormChecksFieldsBeforeTouchingAnything", async () => {
    const { ctx } = await makeTeam();
    const run = (values: Record<string, string>) => changePasswordCore(deps, ctx, formOf(values), null);
    expect(await run({ current: PASSWORD, next: "brand-new1", confirm: "" })).toEqual({
      status: "error",
      message: "Заполните обязательные поля",
    });
    expect(await run({ current: PASSWORD, next: "brand-new1", confirm: "brand-new2" })).toEqual({
      status: "error",
      message: "Введенные пароли не совпадают",
    });
    expect(await run({ current: "wrong-pass1", next: "brand-new1", confirm: "brand-new1" })).toEqual({
      status: "error",
      message: "Неверный логин или пароль",
    });
    expect(await run({ current: PASSWORD, next: "short1", confirm: "short1" })).toEqual({
      status: "error",
      message: "Пароль должен содержать не менее 8 символов, включая буквы и цифры",
    });
    expect(await run({ current: PASSWORD, next: "brand-new1", confirm: "brand-new1" })).toEqual({
      status: "done",
      message: "Пароль изменён",
    });
  });

  it("emailFormGoesThroughBothSteps", async () => {
    const { ctx } = await makeTeam();
    const taken = await addUser("soloPlayer", null);
    const run = (values: Record<string, string>) => emailChangeCore(deps, ctx, formOf(values));
    expect(await run({ step: "request", email: "", password: PASSWORD })).toEqual({ status: "error", message: "Заполните обязательные поля" });
    expect(await run({ step: "request", email: "x@mail.gg", password: "" })).toEqual({
      status: "error",
      message: "Заполните обязательные поля",
    });
    expect(await run({ step: "request", email: "x@mail.gg", password: "wrong-pass1" })).toEqual({
      status: "error",
      message: "Неверный логин или пароль",
    });
    expect(await run({ step: "request", email: "nope", password: PASSWORD })).toEqual({
      status: "error",
      message: "Укажите корректный адрес электронной почты",
    });
    expect(await run({ step: "request", email: taken.email, password: PASSWORD })).toEqual({
      status: "error",
      message: "Этот email уже зарегистрирован",
    });
    expect(await run({ step: "request", email: "Fresh@Mail.gg", password: PASSWORD })).toEqual({ status: "codeSent", email: "fresh@mail.gg" });
    expect(await run({ step: "confirm", code: "" })).toEqual({ status: "error", message: "Заполните обязательные поля" });
    const code = deps.mailer.lastCode("fresh@mail.gg")!;
    const wrong = code === "000000" ? "111111" : "000000";
    expect(await run({ step: "confirm", code: wrong })).toEqual({ status: "error", message: "Неверный код" });
    expect(await run({ step: "confirm", code })).toEqual({ status: "done", message: "Почта изменена" });
    deps.advance(MINUTE);
    await run({ step: "request", email: "later@mail.gg", password: PASSWORD });
    deps.advance(16 * MINUTE);
    expect(await run({ step: "confirm", code: deps.mailer.lastCode("later@mail.gg")! })).toEqual({
      status: "error",
      message: "Срок действия кода истёк. Запросите новый",
    });
  });

  it("deleteFormNeedsThePassword", async () => {
    const { ctx } = await makeTeam();
    expect(await deleteAccountCore(deps, ctx, formOf({ password: "" }))).toEqual({
      status: "error",
      message: "Заполните обязательные поля",
    });
    expect(await deleteAccountCore(deps, ctx, formOf({ password: "wrong-pass1" }))).toEqual({
      status: "error",
      message: "Неверный логин или пароль",
    });
    expect((await deleteAccountCore(deps, ctx, formOf({ password: PASSWORD }))).status).toBe("done");
  });
});
