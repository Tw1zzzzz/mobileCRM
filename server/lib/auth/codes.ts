import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";

export type CodePurpose = "verify" | "reset";

const CODE_TTL_MS = 15 * 60_000;
const MAX_ATTEMPTS = 5;
const CODES_PER_HOUR = 5;
const HOUR_MS = 3_600_000;

interface CodeMail {
  subject: string;
  body: (code: string) => string;
}

const MAIL: Record<CodePurpose, CodeMail> = {
  verify: {
    subject: "Код подтверждения Pulse",
    body: (code) =>
      `Ваш код: ${code}. Он действует 15 минут. Если вы не регистрировались в Pulse, просто проигнорируйте это письмо.`,
  },
  reset: {
    subject: "Сброс пароля Pulse",
    body: (code) =>
      `Код для сброса пароля: ${code}. Он действует 15 минут. Если вы не запрашивали сброс, проигнорируйте письмо — пароль не изменится.`,
  },
};

function generateCode(): string {
  return randomInt(0, 1_000_000).toString().padStart(6, "0");
}

function hashCode(secret: Uint8Array, userId: string, purpose: CodePurpose, code: string): string {
  return createHmac("sha256", secret).update(`${userId}:${purpose}:${code}`).digest("hex");
}

function hashesMatch(a: string, b: string): boolean {
  const left = Buffer.from(a, "hex");
  const right = Buffer.from(b, "hex");
  return left.length === right.length && timingSafeEqual(left, right);
}

/**
 * Voids earlier unconsumed codes of this purpose, stores a fresh one and
 * mails it. Limited to 5 codes per hour per email address.
 */
export async function sendCode(
  deps: Deps,
  user: { id: string; email: string },
  purpose: CodePurpose,
): Promise<void> {
  await rateLimit(deps, `code:${user.email}`, CODES_PER_HOUR, HOUR_MS);

  const code = generateCode();
  const now = deps.now();
  const expiresAt = new Date(now.getTime() + CODE_TTL_MS);
  const codeHash = hashCode(deps.secret, user.id, purpose, code);

  await deps.sql.begin(async (tx) => {
    await tx`
      update email_codes set consumed_at = ${now}
      where user_id = ${user.id} and purpose = ${purpose} and consumed_at is null`;
    await tx`
      insert into email_codes (user_id, purpose, code_hash, expires_at, created_at)
      values (${user.id}, ${purpose}, ${codeHash}, ${expiresAt}, ${now})`;
  });

  const mail = MAIL[purpose];
  try {
    await deps.mailer.send({ to: user.email, subject: mail.subject, text: mail.body(code) });
  } catch (e) {
    console.error("could not send the email code", e);
    throw new ApiError("mail_unavailable", "Could not send the email. Try again later.", 503);
  }
}

interface AttemptRow {
  id: string;
  code_hash: string;
  expires_at: Date;
  attempts: number;
}

/**
 * Checks `code` against the newest unconsumed code of this purpose and
 * consumes it on success. The attempt is counted by one UPDATE ... RETURNING,
 * so concurrent guesses can never exceed the limit.
 */
export async function checkCode(
  deps: Deps,
  userId: string,
  purpose: CodePurpose,
  code: string,
): Promise<void> {
  const [row] = await deps.sql<AttemptRow[]>`
    update email_codes set attempts = attempts + 1
    where id = (
      select id from email_codes
      where user_id = ${userId} and purpose = ${purpose} and consumed_at is null
      order by created_at desc, id desc
      limit 1)
    returning id, code_hash, expires_at, attempts`;

  if (!row) throw new ApiError("invalid_code", "Invalid code.", 422);
  if (row.attempts > MAX_ATTEMPTS) throw tooManyAttempts();
  if (row.expires_at.getTime() <= deps.now().getTime()) {
    throw new ApiError("code_expired", "The code has expired. Request a new one.", 422);
  }

  if (!hashesMatch(row.code_hash, hashCode(deps.secret, userId, purpose, code))) {
    if (row.attempts >= MAX_ATTEMPTS) throw tooManyAttempts();
    throw new ApiError("invalid_code", "Invalid code.", 422);
  }

  const consumed = await deps.sql`
    update email_codes set consumed_at = ${deps.now()}
    where id = ${row.id} and consumed_at is null
    returning id`;
  if (consumed.length === 0) throw new ApiError("invalid_code", "Invalid code.", 422);
}

function tooManyAttempts(): ApiError {
  return new ApiError("too_many_attempts", "Too many attempts. Request a new code.", 429);
}
