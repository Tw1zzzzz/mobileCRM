"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { z } from "zod";
import { defaultDeps } from "@/lib/deps";
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
import { deleteImageCore, saveNoteCore, uploadImageCore } from "@/lib/staff/notes-actions";
import { removeMemberCore, rotateCodeCore, teamHref } from "@/lib/staff/team-management";
import { deleteCalendarEvent, saveCalendarEvent } from "@/lib/staff/calendar";
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
