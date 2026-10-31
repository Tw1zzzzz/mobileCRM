/**
 * The staff panel's way to Faceit matches: a Postgres cache in front of the client, so opening a card does not call
 * Faceit every time and a Faceit outage still shows the last list.
 */
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import { fetchMatchItems, isPlayerId, MATCH_LIMIT, resolvePlayerId, type FaceitCall, type FaceitFailure } from "@/lib/faceit/client";
import { mapMatch, type FaceitMatch } from "@/lib/faceit/mapper";

export const FACEIT_CACHE_TTL_MS = 15 * 60 * 1000;
/** After a failed Faceit fetch nothing asks Faceit again for this long (the stale list, if any, is served meanwhile). */
export const FAILURE_MEMORY_MS = 120_000;

/** One budget for the whole server, so many staff members cannot exhaust the API key. */
const FETCH_LIMIT = 60;
const FETCH_WINDOW_MS = 60_000;
const MAX_NICKNAME_LENGTH = 64;

export type FaceitResult =
  | { status: "ok"; matches: FaceitMatch[]; fetchedAt: Date; stale: boolean }
  | { status: "no-key" | "no-player" | "not-found" | "rate-limited" | "unavailable" };

const REMEMBERED_FAILURES: readonly FaceitFailure[] = ["unavailable", "not-found", "rate-limited"];

interface CacheRow {
  matches: unknown;
  fetched_at: Date | null;
  faceit_ref: string | null;
  failed_at: Date | null;
  failure: string | null;
}

/** What the cache row holds for the player's CURRENT Faceit identity: matches and/or a remembered failure. */
interface Cached {
  matches: FaceitMatch[] | null;
  fetchedAt: Date | null;
  failedAt: Date | null;
  failure: FaceitFailure;
}

/** The identity a row is fetched for: the Faceit player id, or `nick:<nickname>` when it is resolved by nickname. */
function refOf(playerId: string | null, nickname: string | null): string {
  return playerId ?? `nick:${nickname}`;
}

/** The row for this player, or null when there is none or it was fetched for another Faceit account. */
async function readCache(deps: Deps, userId: string, ref: string): Promise<Cached | null> {
  const [row] = await deps.sql<CacheRow[]>`
    select matches, fetched_at, faceit_ref, failed_at, failure from faceit_cache where user_id = ${userId}`;
  if (!row || row.faceit_ref !== ref) return null;
  const hasMatches = Array.isArray(row.matches) && row.fetched_at !== null;
  const failure = REMEMBERED_FAILURES.find((f) => f === row.failure) ?? "unavailable";
  return {
    matches: hasMatches ? (row.matches as FaceitMatch[]) : null,
    fetchedAt: hasMatches ? row.fetched_at : null,
    failedAt: row.failed_at,
    failure,
  };
}

async function writeCache(deps: Deps, userId: string, ref: string, matches: FaceitMatch[], fetchedAt: Date): Promise<void> {
  await deps.sql`
    insert into faceit_cache (user_id, matches, fetched_at, faceit_ref, failed_at, failure)
    values (${userId}, ${deps.sql.json(matches as never)}, ${fetchedAt}, ${ref}, null, null)
    on conflict (user_id) do update set matches = excluded.matches, fetched_at = excluded.fetched_at,
      faceit_ref = excluded.faceit_ref, failed_at = null, failure = null`;
}

/** Remembers a failed fetch. Matches of the same account stay for stale serving; another account's are dropped. */
async function writeFailure(deps: Deps, userId: string, ref: string, failure: FaceitFailure, failedAt: Date): Promise<void> {
  await deps.sql`
    insert into faceit_cache (user_id, matches, fetched_at, faceit_ref, failed_at, failure)
    values (${userId}, null, null, ${ref}, ${failedAt}, ${failure})
    on conflict (user_id) do update set
      matches = case when faceit_cache.faceit_ref = excluded.faceit_ref then faceit_cache.matches else null end,
      fetched_at = case when faceit_cache.faceit_ref = excluded.faceit_ref then faceit_cache.fetched_at else null end,
      faceit_ref = excluded.faceit_ref, failed_at = excluded.failed_at, failure = excluded.failure`;
}

function stale(matches: FaceitMatch[], fetchedAt: Date): FaceitResult {
  return { status: "ok", matches, fetchedAt, stale: true };
}

function failed(cached: Cached | null, failure: FaceitFailure): FaceitResult {
  return cached?.matches && cached.fetchedAt ? stale(cached.matches, cached.fetchedAt) : { status: failure };
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
 * Nothing here throws for a Faceit problem: it becomes a status, or the stale cached list. The caller must already
 * have authorized access to this player: the cache is keyed by user id and shared by all staff.
 */
export async function loadMatches(
  deps: Deps,
  userId: string,
  faceit: { playerId?: string | null; nickname?: string | null },
  http: typeof fetch = fetch,
  apiKey: string | undefined = process.env.FACEIT_API_KEY,
): Promise<FaceitResult> {
  const key = apiKey?.trim();
  if (!key) return { status: "no-key" };
  const knownId = faceit.playerId?.trim();
  const playerId = knownId && isPlayerId(knownId) ? knownId : null;
  const nickname = cleanNickname(faceit.nickname);
  if (playerId === null && nickname === null) return { status: "no-player" };

  const ref = refOf(playerId, nickname);
  const cached = await readCache(deps, userId, ref);
  const now = deps.now().getTime();
  if (cached?.matches && cached.fetchedAt && now - cached.fetchedAt.getTime() < FACEIT_CACHE_TTL_MS) {
    return { status: "ok", matches: cached.matches, fetchedAt: cached.fetchedAt, stale: false };
  }
  // Faceit failed a moment ago: do not ask again yet (stale matches, if there are any, still win).
  if (cached?.failedAt && now - cached.failedAt.getTime() < FAILURE_MEMORY_MS) return failed(cached, cached.failure);
  if (await overLimit(deps)) return failed(cached, "rate-limited");

  const outcome = await fetchFresh(http, key, playerId, nickname);
  if (!outcome.ok) {
    await writeFailure(deps, userId, ref, outcome.failure, deps.now());
    return failed(cached, outcome.failure);
  }

  const fetchedAt = deps.now();
  const matches = outcome.value
    .map((item) => mapMatch(item, fetchedAt))
    .filter((match): match is FaceitMatch => match !== null)
    .slice(0, MATCH_LIMIT);
  await writeCache(deps, userId, ref, matches, fetchedAt);
  return { status: "ok", matches, fetchedAt, stale: false };
}

/** Resolves the nickname when there is no player id, then fetches the raw match items. */
async function fetchFresh(
  http: typeof fetch,
  key: string,
  playerId: string | null,
  nickname: string | null,
): Promise<FaceitCall<unknown[]>> {
  let id = playerId;
  if (id === null) {
    const resolved = await resolvePlayerId(http, key, nickname as string);
    if (!resolved.ok) return resolved;
    id = resolved.value;
  }
  return fetchMatchItems(http, key, id);
}
