/**
 * The staff panel's way to Faceit matches: a Postgres cache in front of the client, so opening a card does not call
 * Faceit every time and a Faceit outage still shows the last list.
 */
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import { fetchMatchItems, isPlayerId, MATCH_LIMIT, resolvePlayerId, type FaceitFailure } from "@/lib/faceit/client";
import { mapMatch, type FaceitMatch } from "@/lib/faceit/mapper";

export const FACEIT_CACHE_TTL_MS = 15 * 60 * 1000;

/** One budget for the whole server, so many staff members cannot exhaust the API key. */
const FETCH_LIMIT = 60;
const FETCH_WINDOW_MS = 60_000;
const MAX_NICKNAME_LENGTH = 64;

export type FaceitResult =
  | { status: "ok"; matches: FaceitMatch[]; fetchedAt: Date; stale: boolean }
  | { status: "no-key" | "no-player" | "not-found" | "rate-limited" | "unavailable" };

interface CacheRow {
  matches: unknown;
  fetched_at: Date;
}

interface CachedMatches {
  matches: FaceitMatch[];
  fetchedAt: Date;
}

async function readCache(deps: Deps, userId: string): Promise<CachedMatches | null> {
  const [row] = await deps.sql<CacheRow[]>`select matches, fetched_at from faceit_cache where user_id = ${userId}`;
  if (!row || !Array.isArray(row.matches)) return null;
  return { matches: row.matches as FaceitMatch[], fetchedAt: row.fetched_at };
}

async function writeCache(deps: Deps, userId: string, matches: FaceitMatch[], fetchedAt: Date): Promise<void> {
  await deps.sql`
    insert into faceit_cache (user_id, matches, fetched_at)
    values (${userId}, ${deps.sql.json(matches as never)}, ${fetchedAt})
    on conflict (user_id) do update set matches = excluded.matches, fetched_at = excluded.fetched_at`;
}

function stale(cached: CachedMatches): FaceitResult {
  return { status: "ok", matches: cached.matches, fetchedAt: cached.fetchedAt, stale: true };
}

function failed(cached: CachedMatches | null, failure: FaceitFailure): FaceitResult {
  return cached ? stale(cached) : { status: failure };
}

async function overLimit(deps: Deps): Promise<boolean> {
  try {
    await rateLimit(deps, "faceit:fetch", FETCH_LIMIT, FETCH_WINDOW_MS);
    return false;
  } catch (error) {
    if (error instanceof ApiError && error.code === "rate_limited") return true;
    throw error;
  }
}

function cleanNickname(value: string | null | undefined): string | null {
  const trimmed = value?.trim() ?? "";
  return trimmed.length > 0 && trimmed.length <= MAX_NICKNAME_LENGTH ? trimmed : null;
}

/**
 * The player's last matches (newest first, at most 100). `userId` is the player's user id (the cache key);
 * `faceit` is what the player's synced `faceit` document says. The key is read from `FACEIT_API_KEY` when called.
 * Nothing here throws for a Faceit problem: it becomes a status, or the stale cached list.
 */
export async function loadMatches(
  deps: Deps,
  userId: string,
  faceit: { playerId?: string | null; nickname?: string | null },
  http: typeof fetch = fetch,
  apiKey: string | undefined = process.env.FACEIT_API_KEY,
): Promise<FaceitResult> {
  if (!apiKey) return { status: "no-key" };
  const knownId = faceit.playerId?.trim();
  const playerId = knownId && isPlayerId(knownId) ? knownId : null;
  const nickname = cleanNickname(faceit.nickname);
  if (playerId === null && nickname === null) return { status: "no-player" };

  const cached = await readCache(deps, userId);
  if (cached && deps.now().getTime() - cached.fetchedAt.getTime() < FACEIT_CACHE_TTL_MS) {
    return { status: "ok", matches: cached.matches, fetchedAt: cached.fetchedAt, stale: false };
  }
  if (await overLimit(deps)) return cached ? stale(cached) : { status: "rate-limited" };

  let id = playerId;
  if (id === null) {
    const resolved = await resolvePlayerId(http, apiKey, nickname as string);
    if (!resolved.ok) return failed(cached, resolved.failure);
    id = resolved.value;
  }
  const items = await fetchMatchItems(http, apiKey, id);
  if (!items.ok) return failed(cached, items.failure);

  const fetchedAt = deps.now();
  const matches = items.value
    .map((item) => mapMatch(item, fetchedAt))
    .filter((match): match is FaceitMatch => match !== null)
    .slice(0, MATCH_LIMIT);
  await writeCache(deps, userId, matches, fetchedAt);
  return { status: "ok", matches, fetchedAt, stale: false };
}
