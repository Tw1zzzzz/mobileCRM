import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import type { StaffContext } from "@/lib/staff/access";
import type { FormState } from "@/lib/staff/form-state";
import { saveGame, type GameInput } from "@/lib/staff/games";

/**
 * The «Внести игру» Server Action without Next.js: `app/staff/actions.ts` resolves the signed-in staff member
 * (`requireStaffContext`) and passes the context in, so the team is always the session's, never the form's.
 * The game arrives as the form built it; `saveGame` validates every field again (the form's own checks are for
 * the person, not for security) and refuses another team's game or player.
 */

export const GAME_NOTICES = { saved: "Игра сохранена" } as const;

export const GAME_ERRORS = {
  invalid: "Игра не сохранена: проверьте заполненные значения",
  notFound: "Игра не найдена. Возможно, её уже удалили",
  failed: "Не удалось сохранить игру. Повторите попытку позже",
} as const;

function failureOf(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "validation_failed") return GAME_ERRORS.invalid;
    if (error.code === "not_found") return GAME_ERRORS.notFound;
  } else {
    console.error("staff game save failed", error);
  }
  return GAME_ERRORS.failed;
}

/** «Сохранить игру»: creates the game, or replaces the one with the form's `id` when it is the team's. */
export async function saveGameCore(deps: Deps, ctx: StaffContext, game: unknown): Promise<FormState> {
  try {
    // `saveGame` runs the game through `parseGameInput` before it reads or writes anything.
    await saveGame(deps, ctx, game as GameInput);
    return { status: "done", message: GAME_NOTICES.saved };
  } catch (e) {
    return { status: "error", message: failureOf(e) };
  }
}
