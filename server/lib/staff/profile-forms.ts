import type { Deps } from "@/lib/deps";
import type { StaffContext } from "@/lib/staff/access";
import type { FormState } from "@/lib/staff/form-state";
import { MESSAGES, PROFILE_NOTICES, profileMessage } from "@/lib/staff/messages";
import {
  changePassword,
  confirmEmailChange,
  deleteOwnAccount,
  requestEmailChange,
  updateProfile,
} from "@/lib/staff/profile";

/**
 * The profile page's Server Actions without Next.js: `app/staff/actions.ts` resolves the signed-in staff member
 * (`requireStaffContext`) and passes the context in, so the person is always the session's, never the form's.
 * Each core turns the form into one `FormState`: a notice on success, the app's text on failure.
 */

function field(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
}

const failure = (message: string): FormState => ({ status: "error", message });
const done = (message: string): FormState => ({ status: "done", message });

async function attempt(work: () => Promise<FormState>): Promise<FormState> {
  try {
    return await work();
  } catch (e) {
    return failure(profileMessage(e));
  }
}

/** «Основное» → «Сохранить». */
export function saveProfileCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<FormState> {
  return attempt(async () => {
    await updateProfile(deps, ctx, { nickname: field(form, "nickname"), faceitUrl: field(form, "faceitUrl") });
    return done(PROFILE_NOTICES.saved);
  });
}

/** «Почта»: the request needs the current password; `step` is «request» («Получить код») or «confirm» («Подтвердить»). */
export function emailChangeCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<FormState> {
  const step = field(form, "step");
  return attempt(async () => {
    if (step === "confirm") {
      const code = field(form, "code");
      if (code.trim() === "") return failure(MESSAGES.emptyFields);
      await confirmEmailChange(deps, ctx, code);
      return done(PROFILE_NOTICES.emailChanged);
    }
    const email = field(form, "email");
    const password = field(form, "password");
    if (email.trim() === "" || password === "") return failure(MESSAGES.emptyFields);
    await requestEmailChange(deps, ctx, email, password);
    return { status: "codeSent", email: email.trim().toLowerCase() };
  });
}

/** «Пароль» → «Сменить пароль»; `keepRefreshHash` is this browser's session, which stays signed in. */
export function changePasswordCore(
  deps: Deps,
  ctx: StaffContext,
  form: FormData,
  keepRefreshHash: string | null,
): Promise<FormState> {
  const current = field(form, "current");
  const next = field(form, "next");
  const confirm = field(form, "confirm");
  if (current === "" || next === "" || confirm === "") return Promise.resolve(failure(MESSAGES.emptyFields));
  if (next !== confirm) return Promise.resolve(failure(MESSAGES.passwordMismatch));
  return attempt(async () => {
    await changePassword(deps, ctx, current, next, keepRefreshHash);
    return done(PROFILE_NOTICES.passwordChanged);
  });
}

/** «Удаление аккаунта» → «Удалить аккаунт»: `done` means the account is gone (the action then ends the session). */
export function deleteAccountCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<FormState> {
  const password = field(form, "password");
  if (password === "") return Promise.resolve(failure(MESSAGES.emptyFields));
  return attempt(async () => {
    await deleteOwnAccount(deps, ctx, password);
    return done("");
  });
}
