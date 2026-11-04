import { MAX_ATTEMPTS, checkCode, sendCode } from "@/lib/auth/codes";
import { hashPassword, verifyPassword } from "@/lib/auth/passwords";
import { revokeAll, revokeAllExcept } from "@/lib/auth/tokens";
import { isStrongPassword, isValidEmail, isValidFaceitUrl, normalizeEmail } from "@/lib/auth/validation";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { deleteAccount } from "@/lib/me";
import { rateLimit } from "@/lib/rateLimit";
import type { StaffContext } from "@/lib/staff/access";
import { isDemoEmail } from "@/lib/users";

/**
 * The staff member's own account on the website: nickname, Faceit link, e-mail, password, sessions, deletion.
 * Every function acts on `ctx.userId` only; nothing here reads an id from a form. Failures are `ApiError`s with
 * the app's codes, which `profileMessage` turns into the app's texts.
 */

export const NICKNAME_MAX = 40;

/** Wrong-password guesses (change password, delete account) per person: a stolen session must not brute-force it. */
const PASSWORD_TRIES = 10;
const PASSWORD_WINDOW_MS = 15 * 60_000;
/** E-mail change codes a person may ask for; `sendCode` adds its own limit per receiving address. */
const EMAIL_REQUESTS = 5;
const EMAIL_REQUESTS_WINDOW_MS = 60 * 60_000;

export interface Profile {
  nickname: string;
  email: string;
  faceitUrl: string | null;
  /** The new address a code was sent to and that is still waiting for it; null when nothing is pending. */
  pendingEmail: string | null;
}

export interface ProfileInput {
  nickname: string;
  faceitUrl: string;
}

/** Which input a `validation_failed` is about; the message is chosen from it (see `profileMessage`). */
export type ProfileField = "nickname_empty" | "nickname_long" | "faceit_url";

function invalid(field: ProfileField, message: string): ApiError {
  return new ApiError("validation_failed", message, 422, { field });
}

export async function loadProfile(deps: Deps, ctx: StaffContext): Promise<Profile> {
  const [row] = await deps.sql<{ nickname: string; email: string; faceit_url: string | null }[]>`
    select nickname, email, faceit_url from users where id = ${ctx.userId}`;
  if (!row) throw new ApiError("unauthorized", "Not authorized", 401);
  // The newest unconsumed code, as long as it can still be used.
  const [pending] = await deps.sql<{ new_email: string }[]>`
    select new_email from email_codes
    where user_id = ${ctx.userId} and purpose = 'email_change' and consumed_at is null
      and expires_at > ${deps.now()} and attempts < ${MAX_ATTEMPTS}
    order by created_at desc, id desc
    limit 1`;
  return {
    nickname: row.nickname,
    email: row.email,
    faceitUrl: row.faceit_url,
    pendingEmail: pending?.new_email ?? null,
  };
}

/** Nickname 1-40 characters; the Faceit link is optional and, when given, a faceit.com link (as in the app). */
export async function updateProfile(
  deps: Deps,
  ctx: StaffContext,
  input: ProfileInput,
): Promise<Pick<Profile, "nickname" | "faceitUrl">> {
  const nickname = input.nickname.trim();
  if (nickname.length < 1) throw invalid("nickname_empty", "nickname: required");
  if (nickname.length > NICKNAME_MAX) throw invalid("nickname_long", `nickname: at most ${NICKNAME_MAX} characters`);
  const link = input.faceitUrl.trim();
  if (link !== "" && !isValidFaceitUrl(link)) throw invalid("faceit_url", "faceit_url: must be a faceit.com link");
  const faceitUrl = link === "" ? null : link;
  await deps.sql`
    update users set nickname = ${nickname}, faceit_url = ${faceitUrl}, updated_at = ${deps.now()}
    where id = ${ctx.userId}`;
  return { nickname, faceitUrl };
}

const wrongPassword = () => new ApiError("invalid_credentials", "Invalid email or password.", 401);

/** Checks the person's current password, counting every try against `PASSWORD_TRIES`. */
async function requirePassword(deps: Deps, userId: string, password: string): Promise<void> {
  await rateLimit(deps, `profile-password:${userId}`, PASSWORD_TRIES, PASSWORD_WINDOW_MS);
  const [row] = await deps.sql<{ password_hash: string }[]>`select password_hash from users where id = ${userId}`;
  if (!row || password === "" || !(await verifyPassword(row.password_hash, password))) throw wrongPassword();
}

/**
 * Sets a new password. The current one is required; every other session is signed out, and the one whose refresh
 * token hashes to `keepRefreshHash` (this browser's) stays. Without it nobody stays signed in.
 */
export async function changePassword(
  deps: Deps,
  ctx: StaffContext,
  current: string,
  next: string,
  keepRefreshHash: string | null,
): Promise<void> {
  if (!isStrongPassword(next)) {
    throw new ApiError("weak_password", "Password must be at least 8 characters with letters and digits.", 422);
  }
  await requirePassword(deps, ctx.userId, current);
  await deps.sql`
    update users set password_hash = ${await hashPassword(next)}, updated_at = ${deps.now()}
    where id = ${ctx.userId}`;
  if (keepRefreshHash === null) await revokeAll(deps, ctx.userId);
  else await revokeAllExcept(deps, ctx.userId, keepRefreshHash);
}

const emailTaken = () => new ApiError("email_taken", "This email is already registered.", 409);

function isEmailConflict(e: unknown): boolean {
  const err = e as { code?: string; constraint_name?: string };
  return err?.code === "23505" && err.constraint_name === "users_email_key";
}

async function addressInUse(deps: Deps, email: string): Promise<boolean> {
  if (isDemoEmail(email)) return true;
  const rows = await deps.sql`select 1 from users where email = ${email}`;
  return rows.length > 0;
}

/**
 * Mails a 6-digit code to the NEW address; the address on the account changes only in `confirmEmailChange`. The
 * current password is required, so a borrowed session cannot redirect the account. An address any account already
 * holds (the person's own included) is refused. A newer request voids the older code.
 */
export async function requestEmailChange(
  deps: Deps,
  ctx: StaffContext,
  rawEmail: string,
  password: string,
): Promise<void> {
  await requirePassword(deps, ctx.userId, password);
  const email = normalizeEmail(rawEmail);
  if (!isValidEmail(email)) throw new ApiError("invalid_email", "Invalid email address.", 422);
  if (await addressInUse(deps, email)) throw emailTaken();
  await rateLimit(deps, `profile-email:${ctx.userId}`, EMAIL_REQUESTS, EMAIL_REQUESTS_WINDOW_MS);
  const [own] = await deps.sql<{ email: string }[]>`select email from users where id = ${ctx.userId}`;
  if (!own) throw new ApiError("unauthorized", "Not authorized", 401);
  await sendCode(deps, { id: ctx.userId, email: own.email }, "email_change", email);
}

/** Applies the pending address once the code is right (15 minutes, 5 tries). Sessions stay. */
export async function confirmEmailChange(deps: Deps, ctx: StaffContext, rawCode: string): Promise<void> {
  const email = await checkCode(deps, ctx.userId, "email_change", rawCode.trim());
  if (email === null) throw new ApiError("invalid_code", "Invalid code.", 422);
  const now = deps.now();
  try {
    await deps.sql`
      update users set email = ${email}, email_verified_at = ${now}, updated_at = ${now}
      where id = ${ctx.userId}`;
  } catch (e) {
    if (isEmailConflict(e)) throw emailTaken();
    throw e;
  }
}

/** Revokes every refresh token of the person: this browser, other browsers and the app. */
export async function signOutEverywhere(deps: Deps, ctx: StaffContext): Promise<void> {
  await revokeAll(deps, ctx.userId);
}

/** The app's account deletion (team handover rules included), after the password. */
export async function deleteOwnAccount(deps: Deps, ctx: StaffContext, password: string): Promise<void> {
  await requirePassword(deps, ctx.userId, password);
  await deleteAccount(deps, ctx.userId);
}
