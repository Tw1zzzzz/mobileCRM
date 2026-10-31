import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rosterPlayer, type StaffContext } from "@/lib/staff/access";
import { CARD_PATH, cardHref, parseCardParams, type CardParams } from "@/lib/staff/card";
import { deleteImage, saveImage, saveNote } from "@/lib/staff/notes";
import { IMAGE_KINDS, IMAGE_MAX_BYTES, type ImageKind, type NoticeToken } from "@/lib/staff/notes-shared";

/**
 * The three Server Actions of the card's «Профиль» without Next.js: `app/staff/actions.ts` resolves the staff
 * member (`requireStaffContext`) and redirects to the URL these return. The team is always `ctx`'s; the player
 * of the form is resolved through the roster, and the redirect is built from the validated card selection and
 * constants only, never from a URL in the form.
 */

/**
 * Anything that cannot be resolved (foreign or unknown player): one answer, without a player in the URL, so the page
 * falls back to the first player's card, where the notice is shown. A request whose player is a valid roster member
 * but whose kind is unknown keeps that player: see `failedHref`.
 */
export const FAILED_HREF = `${CARD_PATH}?notice=failed#notes`;

interface Target {
  playerId: string;
  params: CardParams;
}

function field(form: FormData, name: string): string | undefined {
  const value = form.get(name);
  return typeof value === "string" ? value : undefined;
}

/** The roster-checked player plus the card selection the form carried (invalid values fall back to defaults). */
async function targetOf(deps: Deps, ctx: StaffContext, form: FormData): Promise<Target | null> {
  const id = field(form, "player");
  if (id === undefined) return null;
  const player = await rosterPlayer(deps, ctx, id);
  if (player === null) return null;
  const selection: Record<string, string> = {};
  for (const [name, value] of form.entries()) if (typeof value === "string") selection[name] = value;
  const params = parseCardParams(selection, [player.id]);
  return params === null ? null : { playerId: player.id, params };
}

const back = (target: Target, notice: NoticeToken): string =>
  `${cardHref(target.params, { tab: "profile" })}&notice=${notice}#notes`;

/** «Не удалось» on the selected player's «Профиль» when the player is known and valid, else the generic URL. */
const failedHref = (target: Target | null): string => (target === null ? FAILED_HREF : back(target, "failed"));

const kindOf = (form: FormData): ImageKind | null => IMAGE_KINDS.find((k) => k === field(form, "kind")) ?? null;

export async function saveNoteCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<string> {
  const target = await targetOf(deps, ctx, form);
  if (target === null) return FAILED_HREF;
  try {
    await saveNote(deps, ctx, target.playerId, field(form, "text") ?? "");
  } catch (e) {
    if (e instanceof ApiError && e.status === 422) return back(target, "note-too-long");
    if (e instanceof ApiError && e.status === 404) return failedHref(target);
    throw e;
  }
  return back(target, "note-saved");
}

export async function uploadImageCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<string> {
  const kind = kindOf(form);
  const target = await targetOf(deps, ctx, form);
  if (kind === null || target === null) return failedHref(target);
  const file = form.get("file");
  if (file === null || typeof file === "string" || file.size === 0) return back(target, `${kind}-empty`);
  // The declared size is checked before the bytes are read; saveImage checks the real length again.
  if (file.size > IMAGE_MAX_BYTES) return back(target, `${kind}-size`);
  try {
    await saveImage(deps, ctx, target.playerId, kind, new Uint8Array(await file.arrayBuffer()));
  } catch (e) {
    if (e instanceof ApiError && e.status === 413) return back(target, `${kind}-size`);
    if (e instanceof ApiError && e.status === 422) return back(target, `${kind}-type`);
    if (e instanceof ApiError && e.status === 404) return failedHref(target);
    throw e;
  }
  return back(target, `${kind}-saved`);
}

export async function deleteImageCore(deps: Deps, ctx: StaffContext, form: FormData): Promise<string> {
  const kind = kindOf(form);
  const target = await targetOf(deps, ctx, form);
  if (kind === null || target === null) return failedHref(target);
  try {
    await deleteImage(deps, ctx, target.playerId, kind);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return failedHref(target);
    throw e;
  }
  return back(target, `${kind}-deleted`);
}
