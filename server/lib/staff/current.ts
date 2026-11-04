import { cache } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { defaultDeps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { teamPlayers, type StaffContext } from "@/lib/staff/access";
import { loadNickname } from "@/lib/staff/profile";
import { STAFF_COOKIE, resolveSession, type SessionState } from "@/lib/staff/session";

/** The visitor's session, resolved once per request however many components ask. */
export const getSessionState = cache(async (): Promise<SessionState> => {
  const cookie = (await cookies()).get(STAFF_COOKIE)?.value;
  return resolveSession(defaultDeps(), cookie);
});

/** The signed-in staff member's team context; null when nobody (or a non-staff account) is signed in. */
export async function getStaffContext(): Promise<StaffContext | null> {
  const state = await getSessionState();
  return state.kind === "staff" ? state.ctx : null;
}

/**
 * Every page, Server Action and route handler of the panel starts with this: layouts and pages render
 * independently in Next.js, so each turns visitors away itself instead of relying on the layout's check.
 *
 * Pages and actions send visitors to the sign-in / no-access page (the default). A route handler that serves
 * data to an `<img>` or `fetch` passes `"status"`: nobody signed in is an `ApiError` 401, a signed-in account
 * that is not staff of a team a 403, and never an HTML redirect.
 */
export async function requireStaffContext(denied: "redirect" | "status" = "redirect"): Promise<StaffContext> {
  const state = await getSessionState();
  if (state.kind === "staff") return state.ctx;
  if (denied === "status") {
    throw state.kind === "notStaff"
      ? new ApiError("forbidden", "Staff of a team only.", 403)
      : new ApiError("unauthorized", "Not authorized", 401);
  }
  return redirect(state.kind === "notStaff" ? "/staff/no-access" : "/staff/login");
}

/** The signed-in staff member's nickname for the sidebar card, read once per request. */
export const getNickname = cache(async (): Promise<string> => {
  const ctx = await requireStaffContext();
  return loadNickname(defaultDeps(), ctx.userId);
});

/** How many players the signed-in staff member's team has. */
export const getPlayerCount = cache(async (): Promise<number> => {
  const ctx = await requireStaffContext();
  return (await teamPlayers(defaultDeps(), ctx)).length;
});
