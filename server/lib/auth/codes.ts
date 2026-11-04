import { createHmac, randomInt, timingSafeEqual } from "node:crypto";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";

export type CodePurpose = "verify" | "reset" | "email_change";

const CODE_TTL_MS = 15 * 60_000;
/** Tries a code allows before it is dead, even when the right digits come afterwards. */
export const MAX_ATTEMPTS = 5;
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
  email_change: {
    subject: "Смена почты Pulse",
    body: (code) =>
      `Код для подтверждения новой почты: ${code}. Он действует 15 минут. Если вы не меняли почту в Pulse, проигнорируйте письмо.`,
  },
};

function generateCode(): string {
  return randomInt(0, 1_000_000).toString().padStart(6, "0");
}

/** An `email_change` code is bound to the address it was sent to: the hash covers `newEmail` as well. */
function hashCode(
  secret: Uint8Array,
  userId: string,
  purpose: CodePurpose,
  code: string,
  newEmail: string | null = null,
): string {
  const bound = newEmail === null ? "" : `:${newEmail}`;
  return createHmac("sha256", secret).update(`${userId}:${purpose}:${code}${bound}`).digest("hex");
}

function hashesMatch(a: string, b: string): boolean {
  const left = Buffer.from(a, "hex");
  const right = Buffer.from(b, "hex");
  return left.length === right.length && timingSafeEqual(left, right);
}

/**
 * Voids earlier unconsumed codes of this purpose, stores a fresh one and
 * mails it. Limited to 5 codes per hour per email address.
 *
 * `newEmail` is for `email_change` only: the code is mailed to that address instead of the user's own and stays
 * bound to it (`checkCode` hands it back once the code is right).
 */
export async function sendCode(
  deps: Deps,
  user: { id: string; email: string },
  purpose: CodePurpose,
  newEmail: string | null = null,
): Promise<void> {
  if ((purpose === "email_change") !== (newEmail !== null)) {
    throw new Error("sendCode: newEmail goes with the email_change purpose, and only with it");
  }
  const recipient = newEmail ?? user.email;
  await rateLimit(deps, `code:${recipient}`, CODES_PER_HOUR, HOUR_MS);

  const code = generateCode();
  const now = deps.now();
  const expiresAt = new Date(now.getTime() + CODE_TTL_MS);
  const codeHash = hashCode(deps.secret, user.id, purpose, code, newEmail);

  await deps.sql.begin(async (tx) => {
    await tx`
      update email_codes set consumed_at = ${now}
      where user_id = ${user.id} and purpose = ${purpose} and consumed_at is null`;
    await tx`
      insert into email_codes (user_id, purpose, code_hash, expires_at, created_at, new_email)
      values (${user.id}, ${purpose}, ${codeHash}, ${expiresAt}, ${now}, ${newEmail})`;
  });

  const mail = MAIL[purpose];
  try {
    await deps.mailer.send({ to: recipient, subject: mail.subject, text: mail.body(code) });
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
  new_email: string | null;
}

/**
 * Checks `code` against the newest unconsumed code of this purpose and
 * consumes it on success. The attempt is counted by one UPDATE ... RETURNING,
 * so concurrent guesses can never exceed the limit. `alsoValid` false (a wrong
 * password sent along with the code) fails the attempt exactly like a wrong code.
 * Returns the address an `email_change` code was bound to; null for the other purposes.
 */
export async function checkCode(
  deps: Deps,
  userId: string,
  purpose: CodePurpose,
  code: string,
  alsoValid = true,
): Promise<string | null> {
  const [row] = await deps.sql<AttemptRow[]>`
    update email_codes set attempts = attempts + 1
    where id = (
      select id from email_codes
      where user_id = ${userId} and purpose = ${purpose} and consumed_at is null
      order by created_at desc, id desc
      limit 1)
    returning id, code_hash, expires_at, attempts, new_email`;

  if (!row) throw new ApiError("invalid_code", "Invalid code.", 422);
  if (row.attempts > MAX_ATTEMPTS) throw tooManyAttempts();
  if (row.expires_at.getTime() <= deps.now().getTime()) {
    throw new ApiError("code_expired", "The code has expired. Request a new one.", 422);
  }

  if (!hashesMatch(row.code_hash, hashCode(deps.secret, userId, purpose, code, row.new_email)) || !alsoValid) {
    if (row.attempts >= MAX_ATTEMPTS) throw tooManyAttempts();
    throw new ApiError("invalid_code", "Invalid code.", 422);
  }

  const consumed = await deps.sql`
    update email_codes set consumed_at = ${deps.now()}
    where id = ${row.id} and consumed_at is null
    returning id`;
  if (consumed.length === 0) throw new ApiError("invalid_code", "Invalid code.", 422);
  return row.new_email;
}

function tooManyAttempts(): ApiError {
  return new ApiError("too_many_attempts", "Too many attempts. Request a new code.", 429);
}
