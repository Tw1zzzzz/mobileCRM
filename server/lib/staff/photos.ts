import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { parseMealId } from "@/lib/photos";
import { rosterMember, type StaffContext } from "@/lib/staff/access";

/**
 * Meal photos for the staff website. The synced nutrition document only says that a photo exists
 * (`photoRef: true`); the JPEG itself is in `meal_photos`, which the app reads with the player's own token.
 * Staff read it here, and only for players of their own team.
 */

/** A meal photo is health data: the browser must not keep it past the staff session (sign-out, removal of the player). */
const CACHE_CONTROL = "private, no-store";

/**
 * The JPEG of one meal of a player on the caller's roster. `null` for everything else, without telling why:
 * another team's player, a solo player, staff, a removed member, an id that is not a UUID, a meal without a photo.
 */
export async function staffMealPhoto(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  mealId: string,
): Promise<Uint8Array | null> {
  const meal = parseMealId(mealId);
  if (meal === null) return null;
  // The roster is the only gate; `playerId` is compared with roster ids and never reaches SQL otherwise.
  const player = await rosterMember(deps, ctx, playerId);
  if (player === null) return null;
  const [row] = await deps.sql<{ jpeg: Buffer }[]>`
    select jpeg from meal_photos where user_id = ${player.id} and meal_id = ${meal}`;
  return row ? new Uint8Array(row.jpeg) : null;
}

/**
 * The route handler's response: the image, or an empty-bodied status. A session `ApiError` (401 / 403) keeps its
 * status, every other miss is the same 404, and unexpected errors propagate (Next answers 500).
 */
export async function photoResponse(load: () => Promise<Uint8Array | null>): Promise<Response> {
  try {
    const jpeg = await load();
    if (jpeg === null) return new Response(null, { status: 404, headers: { "cache-control": "private, no-store" } });
    return new Response(jpeg as Uint8Array<ArrayBuffer>, {
      status: 200,
      headers: {
        "content-type": "image/jpeg",
        "cache-control": CACHE_CONTROL,
        "x-content-type-options": "nosniff",
      },
    });
  } catch (e) {
    if (e instanceof ApiError) return new Response(null, { status: e.status, headers: { "cache-control": "private, no-store" } });
    throw e;
  }
}
