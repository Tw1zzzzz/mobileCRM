import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError, ok } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import { playerDocuments, requireStaff, teamPlayers, type Collection, type StaffContext } from "@/lib/staff/access";
import { staffMealPhoto } from "@/lib/staff/photos";

/**
 * Bearer-token JSON endpoints for the app's staff screens (`/v1/staff/*`). Thin by design: every privacy rule
 * (own team only, `body-profile` never, team-visible lineups, survey scores only) lives in the access layer
 * (`lib/staff/access.ts`), the same one the staff website reads through.
 */

export const STAFF_REQUESTS_PER_MINUTE = 120;
/** Meal photos share the player-photo bucket of `/v1/photos`: a phone fetches one request per photo. */
export const STAFF_PHOTO_REQUESTS_PER_MINUTE = 600;

async function authorize(req: Request, deps: Deps, bucket: "staff" | "photos", limit: number): Promise<StaffContext> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, `${bucket}:${userId}`, limit, 60_000);
  return requireStaff(deps, userId);
}

async function inviteCode(deps: Deps, teamId: string): Promise<string> {
  const [row] = await deps.sql<{ invite_code: string }[]>`select invite_code from teams where id = ${teamId}`;
  if (!row) throw new ApiError("forbidden", "Staff of a team only.", 403);
  return row.invite_code;
}

/** `GET /v1/staff/team`: the team's name and player invite code, and its players with their last sync time. */
export async function getStaffTeam(req: Request, deps: Deps): Promise<Response> {
  const ctx = await authorize(req, deps, "staff", STAFF_REQUESTS_PER_MINUTE);
  const [code, players] = await Promise.all([inviteCode(deps, ctx.teamId), teamPlayers(deps, ctx)]);
  return ok({
    team: { name: ctx.teamName, invite_code: code },
    players: players.map((p) => ({
      id: p.id,
      nickname: p.nickname,
      last_activity: p.lastActivity === null ? null : p.lastActivity.toISOString(),
    })),
  });
}

/** The `collections` query parameter: a non-empty comma list, names validated by the access layer. */
function requestedCollections(req: Request): Collection[] {
  const raw = new URL(req.url).searchParams.get("collections");
  const names = (raw ?? "").split(",").map((n) => n.trim()).filter((n) => n !== "");
  if (names.length === 0) throw new ApiError("validation_failed", "collections: list at least one collection", 422);
  return [...new Set(names)] as Collection[];
}

/** `GET /v1/staff/players/{id}/documents?collections=a,b`: the player's stored documents after the privacy filter. */
export async function getStaffPlayerDocuments(req: Request, deps: Deps, playerId: string): Promise<Response> {
  const ctx = await authorize(req, deps, "staff", STAFF_REQUESTS_PER_MINUTE);
  const documents = await playerDocuments(deps, ctx, playerId, requestedCollections(req));
  return ok({ documents });
}

/** `GET /v1/staff/players/{id}/photos/{meal_id}`: the raw JPEG, like `/v1/photos`; any miss is `404 not_found`. */
export async function getStaffMealPhoto(req: Request, deps: Deps, playerId: string, mealId: string): Promise<Response> {
  const ctx = await authorize(req, deps, "photos", STAFF_PHOTO_REQUESTS_PER_MINUTE);
  const jpeg = await staffMealPhoto(deps, ctx, playerId, mealId);
  if (jpeg === null) throw new ApiError("not_found", "No photo stored for this meal", 404);
  return new Response(jpeg as Uint8Array<ArrayBuffer>, {
    status: 200,
    headers: { "content-type": "image/jpeg", "cache-control": "private, no-store", "x-content-type-options": "nosniff" },
  });
}
