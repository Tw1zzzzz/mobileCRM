"use server";

import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { z } from "zod";
import { defaultDeps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { clientIp } from "@/lib/rateLimit";
import type { FormState } from "@/lib/staff/form-state";
import { MESSAGES, authMessage } from "@/lib/staff/messages";
import {
  STAFF_COOKIE,
  staffCookieOptions,
  staffResetConfirm,
  staffResetRequest,
  staffSignIn,
  staffSignOut,
} from "@/lib/staff/session";

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

const failure = (message: string): FormState => ({ status: "error", message });

async function ipOfRequest(): Promise<string> {
  return clientIp({ headers: await headers() });
}

async function startSession(token: string): Promise<never> {
  (await cookies()).set(STAFF_COOKIE, token, staffCookieOptions());
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
