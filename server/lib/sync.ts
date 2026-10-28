import { z } from "zod";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { dropOrphanPhotos } from "@/lib/photos";
import { rateLimit } from "@/lib/rateLimit";

/** The only collections a phone may store; the document name is the file name without `.json`. */
export const COLLECTIONS: readonly string[] = [
  "sleep",
  "nutrition",
  "wellbeing",
  "cognitive",
  "calendar",
  "fitness-state",
  "screen-time",
  "lineups",
  "game-goals",
  "faceit",
  "player-survey",
  "body-profile",
];

export const MAX_DOCUMENT_BYTES = 4_000_000;
const MAX_VERSION = 2_147_483_647;
/** Postgres: jsonb cannot hold the NUL character. */
const UNSUPPORTED_UNICODE_ESCAPE = "22P05";

const PutBody = z.object({
  base_version: z.number().int().min(0).max(MAX_VERSION).nullable(),
  // Top-level JSON null would be stored as SQL NULL, which `document jsonb not null` refuses.
  document: z.json().refine((value) => value !== null, "must not be null"),
});

function requireCollection(collection: string): void {
  if (!COLLECTIONS.includes(collection)) {
    throw new ApiError("validation_failed", `Unknown collection: ${collection}`, 422);
  }
}

/** Authenticates and applies the per-user sync rate limit; returns the user id. */
async function authorize(req: Request, deps: Deps): Promise<string> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, "user:" + userId, 120, 60_000);
  return userId;
}

export async function listSync(req: Request, deps: Deps): Promise<Response> {
  const userId = await authorize(req, deps);
  const rows = await deps.sql<{ collection: string; version: number; updated_at: Date }[]>`
    select collection, version, updated_at from sync_documents
    where user_id = ${userId} order by collection`;
  return ok(rows.map((r) => ({ collection: r.collection, version: r.version, updated_at: r.updated_at })));
}

export async function getSyncDocument(req: Request, deps: Deps, collection: string): Promise<Response> {
  const userId = await authorize(req, deps);
  requireCollection(collection);
  const [row] = await deps.sql<{ version: number; updated_at: Date; document: unknown }[]>`
    select version, updated_at, document from sync_documents
    where user_id = ${userId} and collection = ${collection}`;
  if (!row) throw new ApiError("not_found", "No document stored for this collection", 404);
  return ok({ collection, version: row.version, updated_at: row.updated_at, document: row.document });
}

export async function putSyncDocument(req: Request, deps: Deps, collection: string): Promise<Response> {
  const userId = await authorize(req, deps);
  requireCollection(collection);
  const body = await parseBody(req, PutBody, MAX_DOCUMENT_BYTES);
  const now = deps.now();
  const json = deps.sql.json(body.document);

  // The version check and the write are one statement, so two writers holding the
  // same base version cannot both succeed. A nutrition write also drops the photos
  // of removed meals, in the same transaction so a rejected write drops nothing.
  let written: { version: number; updated_at: Date }[];
  try {
    written = await deps.sql.begin(async (tx) => {
      const rows = body.base_version === null
        ? await tx<{ version: number; updated_at: Date }[]>`
            insert into sync_documents (user_id, collection, version, document, updated_at)
            values (${userId}, ${collection}, 1, ${json}, ${now})
            on conflict (user_id, collection) do nothing
            returning version, updated_at`
        : await tx<{ version: number; updated_at: Date }[]>`
            update sync_documents
            set version = version + 1, document = ${json}, updated_at = ${now}
            where user_id = ${userId} and collection = ${collection} and version = ${body.base_version}
            returning version, updated_at`;
      if (rows.length > 0 && collection === "nutrition") {
        await dropOrphanPhotos(tx, userId, body.document);
      }
      return [...rows];
    });
  } catch (e) {
    if ((e as { code?: string }).code === UNSUPPORTED_UNICODE_ESCAPE) {
      throw new ApiError("validation_failed", "document: contains an unsupported character", 422);
    }
    throw e;
  }

  if (written.length === 0) {
    const [current] = await deps.sql<{ version: number }[]>`
      select version from sync_documents where user_id = ${userId} and collection = ${collection}`;
    throw new ApiError("version_conflict", null, 409, { current_version: current?.version ?? 0 });
  }
  return ok({ version: written[0].version, updated_at: written[0].updated_at });
}
