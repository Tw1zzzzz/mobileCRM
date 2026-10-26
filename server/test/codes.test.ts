import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { checkCode, sendCode } from "../lib/auth/codes";
import { ApiError } from "../lib/http";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const MINUTE = 60_000;
const EMAIL = "nova@team.gg";

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

async function insertUser(email = EMAIL): Promise<{ id: string; email: string }> {
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at,
      created_at, updated_at)
    values (${email}, 'Nova', 'x', 'soloPlayer', ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  return { id: row.id, email };
}

async function expectApiError(promise: Promise<unknown>, code: string, status: number) {
  const error = await promise.then(
    () => undefined,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(ApiError);
  expect(error).toMatchObject({ code, status });
}

function wrongCode(code: string): string {
  return code === "000000" ? "111111" : "000000";
}

describe("email codes", () => {
  it("codeIsSixDigitsAndConsumedOnce", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");

    expect(deps.mailer.sent).toHaveLength(1);
    const code = deps.mailer.lastCode(EMAIL)!;
    expect(code).toMatch(/^\d{6}$/);
    expect(deps.mailer.sent[0]).toEqual({
      to: EMAIL,
      subject: "Код подтверждения Pulse",
      text: `Ваш код: ${code}. Он действует 15 минут. Если вы не регистрировались в Pulse, просто проигнорируйте это письмо.`,
    });

    await checkCode(deps, user.id, "verify", code);
    await expectApiError(checkCode(deps, user.id, "verify", code), "invalid_code", 422);
  });

  it("sendsResetCopyForResetPurpose", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "reset");
    const code = deps.mailer.lastCode(EMAIL)!;
    expect(deps.mailer.sent[0].subject).toBe("Сброс пароля Pulse");
    expect(deps.mailer.sent[0].text).toBe(
      `Код для сброса пароля: ${code}. Он действует 15 минут. Если вы не запрашивали сброс, проигнорируйте письмо — пароль не изменится.`,
    );
    // A verify check must not accept a reset code.
    await expectApiError(checkCode(deps, user.id, "verify", code), "invalid_code", 422);
    await checkCode(deps, user.id, "reset", code);
  });

  it("storesAHashNotThePlainCode", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const code = deps.mailer.lastCode(EMAIL)!;
    const [row] = await deps.sql<{ code_hash: string }[]>`
      select code_hash from email_codes where user_id = ${user.id}`;
    expect(row.code_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row.code_hash).not.toContain(code);
  });

  it("codeExpiresAfter15Minutes", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const code = deps.mailer.lastCode(EMAIL)!;
    deps.advance(15 * MINUTE + 1);
    await expectApiError(checkCode(deps, user.id, "verify", code), "code_expired", 422);
  });

  it("codeStillValidJustBeforeExpiry", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const code = deps.mailer.lastCode(EMAIL)!;
    deps.advance(15 * MINUTE - 1);
    await checkCode(deps, user.id, "verify", code);
  });

  it("fifthWrongCodeLocksUntilResend", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const code = deps.mailer.lastCode(EMAIL)!;
    const wrong = wrongCode(code);

    for (let i = 0; i < 4; i++) {
      await expectApiError(checkCode(deps, user.id, "verify", wrong), "invalid_code", 422);
    }
    await expectApiError(checkCode(deps, user.id, "verify", wrong), "too_many_attempts", 429);
    await expectApiError(checkCode(deps, user.id, "verify", code), "too_many_attempts", 429);

    deps.advance(MINUTE);
    await sendCode(deps, user, "verify");
    await checkCode(deps, user.id, "verify", deps.mailer.lastCode(EMAIL)!);
  });

  it("concurrentWrongAttemptsAreCountedExactly", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const code = deps.mailer.lastCode(EMAIL)!;
    const wrong = wrongCode(code);

    const results = await Promise.allSettled(
      Array.from({ length: 8 }, () => checkCode(deps, user.id, "verify", wrong)),
    );
    const codes = results.map((r) => (r.status === "rejected" ? (r.reason as ApiError).code : "ok"));
    expect(codes.filter((c) => c === "invalid_code")).toHaveLength(4);
    expect(codes.filter((c) => c === "too_many_attempts")).toHaveLength(4);
    await expectApiError(checkCode(deps, user.id, "verify", code), "too_many_attempts", 429);
  });

  it("newCodeVoidsOld", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const first = deps.mailer.lastCode(EMAIL)!;
    deps.advance(MINUTE);
    let second = first;
    while (second === first) {
      await sendCode(deps, user, "verify");
      second = deps.mailer.lastCode(EMAIL)!;
    }

    await expectApiError(checkCode(deps, user.id, "verify", first), "invalid_code", 422);
    await checkCode(deps, user.id, "verify", second);
  });

  it("newCodeOfOtherPurposeDoesNotVoid", async () => {
    const user = await insertUser();
    await sendCode(deps, user, "verify");
    const verify = deps.mailer.lastCode(EMAIL)!;
    await sendCode(deps, user, "reset");
    await checkCode(deps, user.id, "verify", verify);
  });

  it("checkWithoutAnyCodeIsInvalid", async () => {
    const user = await insertUser();
    await expectApiError(checkCode(deps, user.id, "verify", "123456"), "invalid_code", 422);
  });

  it("sixthCodeInAnHourIsRateLimited", async () => {
    const user = await insertUser();
    for (let i = 0; i < 5; i++) await sendCode(deps, user, "verify");
    await expectApiError(sendCode(deps, user, "verify"), "rate_limited", 429);
    expect(deps.mailer.sent).toHaveLength(5);
  });
});
