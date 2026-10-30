import { cache } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { defaultDeps } from "@/lib/deps";
import { teamPlayers, type StaffContext } from "@/lib/staff/access";
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
 * Every page of the panel starts with this: layouts and pages render independently in Next.js, so the page
 * turns visitors away itself instead of relying on the layout's check.
 */
export async function requireStaffContext(): Promise<StaffContext> {
  const state = await getSessionState();
  if (state.kind === "staff") return state.ctx;
  return redirect(state.kind === "notStaff" ? "/staff/no-access" : "/staff/login");
}

/** How many players the signed-in staff member's team has. */
export const getPlayerCount = cache(async (): Promise<number> => {
  const ctx = await requireStaffContext();
  return (await teamPlayers(defaultDeps(), ctx)).length;
});
