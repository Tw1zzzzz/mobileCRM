import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rosterPlayer, type StaffContext } from "@/lib/staff/access";
import {
  IMAGE_KINDS,
  IMAGE_MAX_BYTES,
  NOTE_MAX_CHARS,
  noticeOf,
  type ImageKind,
  type Notice,
} from "@/lib/staff/notes-shared";
import { lastActivityLabel } from "@/lib/staff/players";

export { IMAGE_KINDS, IMAGE_MAX_BYTES, NOTE_MAX_CHARS };
export type { ImageKind };

/**
 * Staff notes and images on a player's card. Every function takes the staff context and resolves the player
 * through the roster (`rosterPlayer`), so a player of another team, a solo player, staff, a removed member or a
 * malformed id is simply «no such player» (404). The team always comes from `ctx`; text and image bytes are
 * stored as given and only ever rendered as plain text / served as an image with the sniffed type.
 * Rows go with the player: account deletion and the team cascade by foreign key, `removeMember` deletes them.
 */

const notFound = () => new ApiError("not_found", "No such player in your team.", 404);

async function rosterId(deps: Deps, ctx: StaffContext, playerId: string): Promise<string> {
  const player = await rosterPlayer(deps, ctx, playerId);
  if (player === null) throw notFound();
  return player.id;
}

function imageKind(kind: string): ImageKind {
  const found = IMAGE_KINDS.find((k) => k === kind);
  if (found === undefined) throw new ApiError("validation_failed", "Unknown image kind.", 422);
  return found;
}

// ---------------------------------------------------------------- notes

/** Line breaks as LF (a browser submits CRLF) and no NUL (Postgres text cannot hold it). */
function cleanNote(text: string): string {
  return text.replace(/\r\n?/g, "\n").replace(/\u0000/g, "");
}

/** The shared note of the player; one per player, the last write wins. */
export async function saveNote(deps: Deps, ctx: StaffContext, playerId: string, text: string): Promise<void> {
  const id = await rosterId(deps, ctx, playerId);
  const note = cleanNote(text);
  if (note.length > NOTE_MAX_CHARS) {
    throw new ApiError("validation_failed", `A note is at most ${NOTE_MAX_CHARS} characters.`, 422);
  }
  await deps.sql`
    insert into staff_player_notes (player_id, team_id, communication, updated_at, updated_by)
    values (${id}, ${ctx.teamId}, ${note}, ${deps.now()}, ${ctx.userId})
    on conflict (player_id) do update set
      team_id = excluded.team_id,
      communication = excluded.communication,
      updated_at = excluded.updated_at,
      updated_by = excluded.updated_by`;
}

// ---------------------------------------------------------------- images

export type ImageType = "image/png" | "image/jpeg" | "image/webp";

const startsWith = (bytes: Uint8Array, prefix: readonly number[], at = 0): boolean =>
  bytes.length >= at + prefix.length && prefix.every((b, i) => bytes[at + i] === b);

/** The type of an image by its first bytes (PNG, JPEG, WebP); anything else, SVG and HTML included, is null. */
export function sniffImage(bytes: Uint8Array): ImageType | null {
  if (startsWith(bytes, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return "image/png";
  if (startsWith(bytes, [0xff, 0xd8, 0xff])) return "image/jpeg";
  // "RIFF" <4 bytes of length> "WEBP"
  if (startsWith(bytes, [0x52, 0x49, 0x46, 0x46]) && startsWith(bytes, [0x57, 0x45, 0x42, 0x50], 8)) return "image/webp";
  return null;
}

/** Stores (or replaces) one of the player's three images. The type comes from the bytes, never from the client. */
export async function saveImage(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  kind: ImageKind,
  bytes: Uint8Array,
): Promise<void> {
  const id = await rosterId(deps, ctx, playerId);
  const checked = imageKind(kind);
  if (bytes.length === 0) throw new ApiError("validation_failed", "The file is empty.", 422);
  if (bytes.length > IMAGE_MAX_BYTES) throw new ApiError("payload_too_large", "The image is too large.", 413);
  const contentType = sniffImage(bytes);
  if (contentType === null) throw new ApiError("validation_failed", "Only PNG, JPEG and WebP images.", 422);
  await deps.sql`
    insert into staff_player_images (player_id, kind, team_id, content_type, bytes, updated_at, updated_by)
    values (${id}, ${checked}, ${ctx.teamId}, ${contentType}, ${bytes}, ${deps.now()}, ${ctx.userId})
    on conflict (player_id, kind) do update set
      team_id = excluded.team_id,
      content_type = excluded.content_type,
      bytes = excluded.bytes,
      updated_at = excluded.updated_at,
      updated_by = excluded.updated_by`;
}

export async function deleteImage(deps: Deps, ctx: StaffContext, playerId: string, kind: ImageKind): Promise<void> {
  const id = await rosterId(deps, ctx, playerId);
  const checked = imageKind(kind);
  await deps.sql`
    delete from staff_player_images where player_id = ${id} and kind = ${checked} and team_id = ${ctx.teamId}`;
}

export interface StoredImage {
  contentType: string;
  bytes: Uint8Array;
}

/** The image of a player on the caller's roster; null for everything else, without telling why. */
export async function staffImage(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  kind: string,
): Promise<StoredImage | null> {
  const wanted = IMAGE_KINDS.find((k) => k === kind);
  if (wanted === undefined) return null;
  const player = await rosterPlayer(deps, ctx, playerId);
  if (player === null) return null;
  const [row] = await deps.sql<{ content_type: string; bytes: Buffer }[]>`
    select content_type, bytes from staff_player_images
    where player_id = ${player.id} and kind = ${wanted} and team_id = ${ctx.teamId}`;
  return row ? { contentType: row.content_type, bytes: new Uint8Array(row.bytes) } : null;
}

const NO_STORE = { "cache-control": "private, no-store" };

/**
 * The route handler's response: the image, or an empty-bodied status. A session `ApiError` (401 / 403) keeps its
 * status, every other miss is the same 404, and unexpected errors propagate (Next answers 500). The headers keep
 * a hostile file harmless: the sniffed type, `nosniff`, and a CSP that would sandbox it if opened as a page.
 */
export async function imageResponse(load: () => Promise<StoredImage | null>): Promise<Response> {
  try {
    const image = await load();
    if (image === null) return new Response(null, { status: 404, headers: NO_STORE });
    return new Response(image.bytes as Uint8Array<ArrayBuffer>, {
      status: 200,
      headers: {
        "content-type": image.contentType,
        "content-disposition": "inline",
        "x-content-type-options": "nosniff",
        "content-security-policy": "default-src 'none'; sandbox; frame-ancestors 'none'",
        ...NO_STORE,
      },
    });
  } catch (e) {
    if (e instanceof ApiError) return new Response(null, { status: e.status, headers: NO_STORE });
    throw e;
  }
}

// ---------------------------------------------------------------- what the card shows

export interface NoteView {
  text: string;
  /** «Изменено сегодня, 15:00 · Coach»; the author is left out when that account no longer exists. */
  changed: string;
}

export interface ImageView {
  /** `updated_at` in ms: the `?v=` of the image URL, so a replaced image is not served from the cache. */
  version: number;
}

export interface ProfileNotes {
  note: NoteView | null;
  images: Record<ImageKind, ImageView | null>;
  notice: Notice | null;
}

/** The notes block of a roster-checked player (`playerId` comes from the roster, not from the URL). */
export async function loadProfileNotes(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  tz: string,
  notice?: string,
): Promise<ProfileNotes> {
  const [notes, images] = await Promise.all([
    deps.sql<{ communication: string; updated_at: Date; nickname: string | null }[]>`
      select n.communication, n.updated_at, u.nickname
      from staff_player_notes n left join users u on u.id = n.updated_by
      where n.player_id = ${playerId} and n.team_id = ${ctx.teamId}`,
    deps.sql<{ kind: string; updated_at: Date }[]>`
      select kind, updated_at from staff_player_images
      where player_id = ${playerId} and team_id = ${ctx.teamId}`,
  ]);
  const held = notes[0];
  const version = (kind: ImageKind): ImageView | null => {
    const row = images.find((r) => r.kind === kind);
    return row ? { version: row.updated_at.getTime() } : null;
  };
  return {
    note: held
      ? {
          text: held.communication,
          changed: `Изменено ${lastActivityLabel(held.updated_at, deps.now(), tz)}${held.nickname === null ? "" : ` · ${held.nickname}`}`,
        }
      : null,
    images: { scheme: version("scheme"), roadmap: version("roadmap"), mindmap: version("mindmap") },
    notice: noticeOf(notice),
  };
}
