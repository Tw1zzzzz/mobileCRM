"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { z } from "zod";
import { defaultDeps } from "@/lib/deps";
import { hashRefreshToken } from "@/lib/auth/tokens";
import { ApiError } from "@/lib/http";
import { clientIp } from "@/lib/rateLimit";
import type { FormState } from "@/lib/staff/form-state";
import { requireStaffContext } from "@/lib/staff/current";
import { MESSAGES, authMessage } from "@/lib/staff/messages";
import {
  STAFF_COOKIE,
  replaceStaffSession,
  staffCookieOptions,
  staffResetConfirm,
  staffResetRequest,
  staffSignIn,
  staffSignOut,
} from "@/lib/staff/session";
import { changePasswordCore, deleteAccountCore, emailChangeCore, saveProfileCore } from "@/lib/staff/profile-forms";
import { signOutEverywhere } from "@/lib/staff/profile";
import { deleteImageCore, saveNoteCore, uploadImageCore } from "@/lib/staff/notes-actions";
import { removeMemberCore, rotateCodeCore, teamHref } from "@/lib/staff/team-management";
import { deleteCalendarEvent, saveCalendarEvent } from "@/lib/staff/calendar";
import { deleteStaffLineup, saveStaffLineup, toggleStaffLineupFavorite } from "@/lib/staff/lineups";
import { revalidatePath } from "next/cache";

const SignInFields = z.object({ email: z.string().trim().min(1), password: z.string().min(1) });
const RequestFields = z.object({ email: z.string().trim().min(1) });
const ConfirmFields = z.object({
  email: z.string().trim().min(1),
  code: z.string().trim().min(1),
  password: z.string().min(1),
  confirm: z.string().min(1),
});

function fields(form: FormData): Record<string, string> {
  const values: Record<string, string> = {};
  for (const [key, value] of form.entries()) if (typeof value === "string") values[key] = value;
  return values;
}

function field(form: FormData, name: string): string | undefined {
  const value = form.get(name);
  return typeof value === "string" ? value : undefined;
}

const failure = (message: string): FormState => ({ status: "error", message });

async function ipOfRequest(): Promise<string> {
  return clientIp({ headers: await headers() });
}

async function startSession(token: string): Promise<never> {
  const jar = await cookies();
  // Signing in again from a browser that still has a session: the old token must not outlive it.
  await replaceStaffSession(defaultDeps(), jar.get(STAFF_COOKIE)?.value, token);
  jar.set(STAFF_COOKIE, token, staffCookieOptions());
  redirect("/staff");
}

export async function signInAction(_previous: FormState, form: FormData): Promise<FormState> {
  const input = SignInFields.safeParse(fields(form));
  if (!input.success) return failure(MESSAGES.emptyFields);
  let token = "";
  let unverified = false;
  try {
    token = await staffSignIn(defaultDeps(), await ipOfRequest(), input.data.email, input.data.password);
  } catch (e) {
    if (!(e instanceof ApiError && e.code === "email_not_verified")) return failure(authMessage(e));
    unverified = true;
  }
  if (unverified) redirect("/staff/login?verify=1");
  return startSession(token);
}

export async function requestResetAction(_previous: FormState, form: FormData): Promise<FormState> {
  const input = RequestFields.safeParse(fields(form));
  if (!input.success) return failure(MESSAGES.emptyFields);
  try {
    await staffResetRequest(defaultDeps(), await ipOfRequest(), input.data.email);
  } catch (e) {
    return failure(authMessage(e));
  }
  return { status: "codeSent", email: input.data.email };
}

export async function confirmResetAction(_previous: FormState, form: FormData): Promise<FormState> {
  const input = ConfirmFields.safeParse(fields(form));
  if (!input.success) return failure(MESSAGES.emptyFields);
  if (input.data.password !== input.data.confirm) return failure(MESSAGES.passwordMismatch);
  let token: string;
  try {
    token = await staffResetConfirm(
      defaultDeps(),
      await ipOfRequest(),
      input.data.email,
      input.data.code,
      input.data.password,
    );
  } catch (e) {
    return failure(authMessage(e));
  }
  return startSession(token);
}

export async function signOutAction(): Promise<void> {
  const jar = await cookies();
  await staffSignOut(defaultDeps(), jar.get(STAFF_COOKIE)?.value);
  jar.delete(STAFF_COOKIE);
  redirect("/staff/login");
}

// ---- «Игроки → Команда» ----
// Every action starts with requireStaffContext(): a logged-out or non-staff caller is redirected before anything
// is read or written. The team always comes from that context, never from the form.

export async function rotateCodeAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const result = await rotateCodeCore(defaultDeps(), ctx, form);
  redirect(teamHref(result.ok ? undefined : result.error));
}

export async function removeMemberAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const result = await removeMemberCore(defaultDeps(), ctx, form);
  redirect(teamHref(result.ok ? undefined : result.error));
}

// ---- Team calendar ----
export async function saveCalendarEventAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const result = await saveCalendarEvent(defaultDeps(), ctx, form);
  if (!result.ok) redirect(`/staff/calendar?error=${result.error}`);
  revalidatePath("/staff/calendar");
  redirect(`/staff/calendar?month=${result.month}&day=${result.day}`);
}

export async function deleteCalendarEventAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const id = field(form, "id");
  if (!id || !z.string().uuid().safeParse(id).success) redirect("/staff/calendar?error=not-found");
  const deleted = await deleteCalendarEvent(defaultDeps(), ctx, id);
  revalidatePath("/staff/calendar");
  redirect(deleted ? "/staff/calendar" : "/staff/calendar?error=not-found");
}

// ---- Team lineups and scripts ----
/**
 * Where a lineup action sends the person back to. `prefix` names the form's filter fields: the save form calls its
 * own `map`, `type` and `side` fields by those names, so its filters travel as `return_map` and the like.
 */
function lineupReturn(form: FormData, prefix: "" | "return_", entry?: string, notice?: string, error?: string): string {
  const query = new URLSearchParams();
  for (const key of ["q", "map", "type", "side"] as const) {
    const value = (field(form, `${prefix}${key}`) ?? "").trim();
    if (value && value !== "all") query.set(key, value.slice(0, 100));
  }
  if (field(form, `${prefix}fav`) === "1") query.set("fav", "1");
  if (entry) query.set("entry", entry);
  // A refused save reopens the dialog it came from.
  const dialog = field(form, "return_form");
  if (error && (dialog === "lineup" || dialog === "script" || dialog === "edit")) query.set("form", dialog);
  if (notice) query.set("notice", notice);
  if (error) query.set("error", error);
  const suffix = query.toString();
  return `/staff/lineups${suffix ? `?${suffix}` : ""}`;
}

export async function saveStaffLineupAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const result = await saveStaffLineup(defaultDeps(), ctx, form);
  if (!result) redirect(lineupReturn(form, "return_", field(form, "id") || undefined, undefined, "invalid"));
  revalidatePath("/staff/lineups");
  redirect(lineupReturn(form, "return_", result.id, result.created ? "created" : "saved"));
}

export async function deleteStaffLineupAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const id = field(form, "id");
  if (!id || !z.string().uuid().safeParse(id).success) redirect(lineupReturn(form, "", undefined, undefined, "not-found"));
  const deleted = await deleteStaffLineup(defaultDeps(), ctx, id);
  revalidatePath("/staff/lineups");
  redirect(lineupReturn(form, "", undefined, deleted ? "deleted" : undefined, deleted ? undefined : "not-found"));
}

export async function toggleStaffLineupFavoriteAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  const id = field(form, "id");
  if (!id || !z.string().uuid().safeParse(id).success) redirect(lineupReturn(form, "", undefined, undefined, "not-found"));
  const found = await toggleStaffLineupFavorite(defaultDeps(), ctx, id);
  revalidatePath("/staff/lineups");
  // A star on a card keeps the entry that is open in the detail panel; without one the toggled entry opens.
  const kept = field(form, "selected");
  const open = z.string().uuid().safeParse(kept).success ? kept : id;
  redirect(lineupReturn(form, "", open, undefined, found ? undefined : "not-found"));
}

// ---- «Игроки → карточка → Профиль»: notes and images ----
// Same rule: the session check first, the team from the context, the player resolved through the roster.

export async function saveNoteAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  redirect(await saveNoteCore(defaultDeps(), ctx, form));
}

export async function uploadImageAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  redirect(await uploadImageCore(defaultDeps(), ctx, form));
}

export async function deleteImageAction(form: FormData): Promise<void> {
  const ctx = await requireStaffContext();
  redirect(await deleteImageCore(defaultDeps(), ctx, form));
}

// ---- Профиль ----
// The person is always the session's: the context comes from requireStaffContext(), never from the form.

const PROFILE_PATH = "/staff/profile";

export async function saveProfileAction(_previous: FormState, form: FormData): Promise<FormState> {
  const ctx = await requireStaffContext();
  const result = await saveProfileCore(defaultDeps(), ctx, form);
  if (result.status === "done") revalidatePath(PROFILE_PATH);
  return result;
}

/** Both steps of the e-mail change; the page re-renders so that the code field appears (or goes) with the pending code. */
export async function emailChangeAction(_previous: FormState, form: FormData): Promise<FormState> {
  const ctx = await requireStaffContext();
  const result = await emailChangeCore(defaultDeps(), ctx, form);
  if (result.status !== "error") revalidatePath(PROFILE_PATH);
  return result;
}

export async function changePasswordAction(_previous: FormState, form: FormData): Promise<FormState> {
  const ctx = await requireStaffContext();
  // This browser's own session stays; every other one is signed out.
  const cookie = (await cookies()).get(STAFF_COOKIE)?.value;
  return changePasswordCore(defaultDeps(), ctx, form, cookie ? hashRefreshToken(cookie) : null);
}

export async function signOutEverywhereAction(): Promise<void> {
  const ctx = await requireStaffContext();
  await signOutEverywhere(defaultDeps(), ctx);
  (await cookies()).delete(STAFF_COOKIE);
  redirect("/staff/login");
}

export async function deleteAccountAction(_previous: FormState, form: FormData): Promise<FormState> {
  const ctx = await requireStaffContext();
  const result = await deleteAccountCore(defaultDeps(), ctx, form);
  if (result.status !== "done") return result;
  (await cookies()).delete(STAFF_COOKIE);
  return redirect("/staff/login");
}
