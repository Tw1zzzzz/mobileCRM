import { z } from "zod";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, readBytesLimited } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";

export const MAX_PHOTO_BYTES = 1_000_000;
const JPEG_MAGIC = [0xff, 0xd8, 0xff];

const MealId = z.guid();

function requireMealId(mealId: string): string {
  const parsed = MealId.safeParse(mealId);
  if (!parsed.success) throw new ApiError("validation_failed", "meal_id: must be a UUID", 422);
  return parsed.data;
}

/** Authenticates and applies the per-user rate limit shared with sync; returns the user id. */
async function authorize(req: Request, deps: Deps): Promise<string> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, "user:" + userId, 120, 60_000);
  return userId;
}

export async function putPhoto(req: Request, deps: Deps, mealId: string): Promise<Response> {
  const userId = await authorize(req, deps);
  const meal = requireMealId(mealId);
  const jpeg = await readBytesLimited(req, MAX_PHOTO_BYTES);
  if (!JPEG_MAGIC.every((byte, i) => jpeg[i] === byte)) {
    throw new ApiError("validation_failed", "Body must be a JPEG image", 422);
  }
  await deps.sql`
    insert into meal_photos (user_id, meal_id, jpeg, updated_at)
    values (${userId}, ${meal}, ${jpeg}, ${deps.now()})
    on conflict (user_id, meal_id) do update set jpeg = excluded.jpeg, updated_at = excluded.updated_at`;
  return ok({});
}

export async function getPhoto(req: Request, deps: Deps, mealId: string): Promise<Response> {
  const userId = await authorize(req, deps);
  const meal = requireMealId(mealId);
  const [row] = await deps.sql<{ jpeg: Buffer }[]>`
    select jpeg from meal_photos where user_id = ${userId} and meal_id = ${meal}`;
  if (!row) throw new ApiError("not_found", "No photo stored for this meal", 404);
  return new Response(new Uint8Array(row.jpeg), {
    status: 200,
    headers: { "content-type": "image/jpeg", "cache-control": "private, no-store" },
  });
}

/**
 * Meal ids (lower-cased, to match uuid text) listed in a nutrition document, or
 * null when the document has no `meals` list and so says nothing about photos.
 */
export function nutritionMealIds(document: unknown): string[] | null {
  if (typeof document !== "object" || document === null) return null;
  const meals = (document as { meals?: unknown }).meals;
  if (!Array.isArray(meals)) return null;
  const ids: string[] = [];
  for (const meal of meals) {
    const id = (meal as { id?: unknown } | null)?.id;
    if (typeof id === "string") ids.push(id.toLowerCase());
  }
  return ids;
}

type Tx = Parameters<Parameters<Deps["sql"]["begin"]>[1]>[0];

/** Deletes the user's photos whose meal is no longer in the nutrition document. */
export async function dropOrphanPhotos(tx: Tx, userId: string, document: unknown): Promise<void> {
  const ids = nutritionMealIds(document);
  if (ids === null) return;
  await tx`
    delete from meal_photos
    where user_id = ${userId} and not (meal_id::text = any(${tx.array(ids, 25)}))`;
}
