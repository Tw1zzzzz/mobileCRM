import { z } from "zod";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import type { StaffContext } from "@/lib/staff/access";
import { removeMember, rotateCode } from "@/lib/team/members";

/**
 * The two team actions of «Игроки → Команда» without any Next.js: the Server Actions in `app/staff/actions.ts`
 * resolve the signed-in staff member (`requireStaffContext`) and pass that context in. The team is always the
 * one of `ctx`; a team id in the form is never read, and a member id is resolved inside the caller's team.
 */

export const TEAM_PATH = "/staff/players";

/** Outcomes shown inline after an action. Anything else is an unexpected error and is not swallowed. */
export type TeamError = "last-staff" | "failed";
export const TEAM_ERRORS: readonly TeamError[] = ["last-staff", "failed"];

export const TEAM_ERROR_TEXT: Record<TeamError, string> = {
  "last-staff": "Нельзя убрать последнего стаффа команды",
  failed: "Не удалось выполнить действие. Обновите страницу и попробуйте ещё раз.",
};

export const ROTATE_CONFIRM_TEXT = "Старый код перестанет работать. Продолжить?";
export const removeConfirmText = (nickname: string): string =>
  `${nickname} станет соло-игроком и пропадёт из панели. Продолжить?`;

export type TeamActionResult = { ok: true } | { ok: false; error: TeamError };

const done: TeamActionResult = { ok: true };
const failed: TeamActionResult = { ok: false, error: "failed" };

const RotateFields = z.object({ kind: z.enum(["player", "staff"]) });
const RemoveFields = z.object({ memberId: z.string().uuid() });

function field(form: FormData, name: string): string | undefined {
  const value = form.get(name);
  return typeof value === "string" ? value : undefined;
}

/** The page every action returns to; the query is made of constants only. */
export function teamHref(error?: TeamError): string {
  return error === undefined ? `${TEAM_PATH}#team` : `${TEAM_PATH}?error=${error}#team`;
}

/** «Новый код»: replaces the player or the staff code of the caller's team. Plain call, no transaction. */
export async function rotateCodeCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<TeamActionResult> {
  const input = RotateFields.safeParse({ kind: field(form, "kind") });
  if (!input.success) return failed;
  await rotateCode(deps, ctx.teamId, input.data.kind);
  return done;
}

/** «Убрать из команды»: the member becomes a solo player. Another team's member is simply not found. */
export async function removeMemberCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<TeamActionResult> {
  const input = RemoveFields.safeParse({ memberId: field(form, "memberId") });
  if (!input.success) return failed;
  try {
    await removeMember(deps, ctx.teamId, ctx.userId, input.data.memberId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 422) return { ok: false, error: "last-staff" };
    if (e instanceof ApiError && e.status === 404) return failed;
    throw e;
  }
  return done;
}
