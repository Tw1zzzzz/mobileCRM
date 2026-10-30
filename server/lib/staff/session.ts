import { authenticate } from "@/lib/auth/login";
import { confirmPasswordReset, requestPasswordReset } from "@/lib/auth/reset";
import { issueSession, refreshTokenUserId, revokeRefresh } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import { requireStaff, type StaffContext } from "@/lib/staff/access";

/** The staff website's session cookie; its value is a refresh token issued by `issueSession`. */
export const STAFF_COOKIE = "pulse_staff";

const COOKIE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;
/** Sign-in and reset share the app's limit: the same `auth:<ip>` bucket, 10 requests a minute. */
const AUTH_LIMIT = 10;
const AUTH_WINDOW_MS = 60_000;

export interface StaffCookieOptions {
  httpOnly: true;
  secure: boolean;
  sameSite: "lax";
  path: "/";
  maxAge: number;
}

/** `Secure` everywhere except local development, where the site runs on plain http. */
export function staffCookieOptions(nodeEnv: string | undefined = process.env.NODE_ENV): StaffCookieOptions {
  return {
    httpOnly: true,
    secure: nodeEnv !== "development",
    sameSite: "lax",
    path: "/",
    maxAge: COOKIE_MAX_AGE_SECONDS,
  };
}

export type SessionState =
  /** No cookie, or a revoked / expired / unknown session. */
  | { kind: "none" }
  /** A valid session of someone who is not staff of a team. */
  | { kind: "notStaff" }
  | { kind: "staff"; ctx: StaffContext };

/** What the cookie stands for. The token is only looked up, never exchanged for a new one. */
export async function resolveSession(deps: Deps, cookieValue: string | null | undefined): Promise<SessionState> {
  if (!cookieValue) return { kind: "none" };
  const userId = await refreshTokenUserId(deps, cookieValue);
  if (!userId) return { kind: "none" };
  try {
    return { kind: "staff", ctx: await requireStaff(deps, userId) };
  } catch (e) {
    if (!(e instanceof ApiError)) throw e;
    return e.code === "forbidden" ? { kind: "notStaff" } : { kind: "none" };
  }
}

/** The signed-in staff member's team context; null for anyone else. */
export async function staffSession(deps: Deps, cookieValue: string | null | undefined): Promise<StaffContext | null> {
  const state = await resolveSession(deps, cookieValue);
  return state.kind === "staff" ? state.ctx : null;
}

function limitAuth(deps: Deps, ip: string): Promise<void> {
  return rateLimit(deps, "auth:" + ip, AUTH_LIMIT, AUTH_WINDOW_MS);
}

/** Checks the credentials like the app's sign-in and returns the value for the cookie. */
export async function staffSignIn(deps: Deps, ip: string, email: string, password: string): Promise<string> {
  await limitAuth(deps, ip);
  const user = await authenticate(deps, email, password);
  return (await issueSession(deps, user.id)).refresh_token;
}

/**
 * Called once a new session exists: a browser that signs in again still carries the previous cookie, whose token
 * would otherwise stay valid for 30 days with nobody holding it. Revokes that one token (only this browser's).
 */
export async function replaceStaffSession(
  deps: Deps,
  previousCookie: string | null | undefined,
  newToken: string,
): Promise<void> {
  if (previousCookie && previousCookie !== newToken) await revokeRefresh(deps, previousCookie);
}

/** Ends this browser's session only. Unknown or missing cookies are ignored. */
export async function staffSignOut(deps: Deps, cookieValue: string | null | undefined): Promise<void> {
  if (cookieValue) await revokeRefresh(deps, cookieValue);
}

export async function staffResetRequest(deps: Deps, ip: string, email: string): Promise<void> {
  await limitAuth(deps, ip);
  await requestPasswordReset(deps, email);
}

/** Sets the new password and signs in with it; returns the value for the cookie. */
export async function staffResetConfirm(
  deps: Deps,
  ip: string,
  email: string,
  code: string,
  newPassword: string,
): Promise<string> {
  await limitAuth(deps, ip);
  const userId = await confirmPasswordReset(deps, email, code, newPassword);
  return (await issueSession(deps, userId)).refresh_token;
}
