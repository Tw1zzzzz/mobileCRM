/**
 * Faceit Data API v4: the two calls the staff panel needs. The HTTP function is injected so tests never touch the
 * network. The key goes only into the `Authorization` header of requests to the fixed base URL; it is never logged
 * and never part of an error.
 */

export const FACEIT_BASE_URL = "https://open.faceit.com/data/v4";
export const FACEIT_GAME = "cs2";
/** The same cap as the app (`GameStore.MATCH_LIMIT`). */
export const MATCH_LIMIT = 100;
export const REQUEST_TIMEOUT_MS = 5_000;

export type FaceitFailure = "unavailable" | "not-found" | "rate-limited";

export type FaceitCall<T> = { ok: true; value: T } | { ok: false; failure: FaceitFailure };

/** Ids that may go into a URL path: Faceit player ids are UUIDs. Synced data comes from the player's phone. */
const PLAYER_ID = /^[A-Za-z0-9-]{1,64}$/;

export function isPlayerId(value: string): boolean {
  return PLAYER_ID.test(value);
}

function failureOf(status: number): FaceitFailure {
  if (status === 404) return "not-found";
  if (status === 429) return "rate-limited";
  return "unavailable"; // 401/403 (bad key), 5xx, anything else
}

async function getJson(http: typeof fetch, apiKey: string, url: string): Promise<FaceitCall<unknown>> {
  let response: Response;
  try {
    response = await http(url, {
      headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      redirect: "error", // only open.faceit.com is ever called; the key must not follow a redirect elsewhere
    });
  } catch {
    return { ok: false, failure: "unavailable" }; // network, timeout
  }
  if (!response.ok) return { ok: false, failure: failureOf(response.status) };
  try {
    return { ok: true, value: await response.json() };
  } catch {
    return { ok: false, failure: "unavailable" }; // unreadable body
  }
}

/** `GET /players?nickname=&game=cs2` -> the player's id. */
export async function resolvePlayerId(http: typeof fetch, apiKey: string, nickname: string): Promise<FaceitCall<string>> {
  const url = `${FACEIT_BASE_URL}/players?nickname=${encodeURIComponent(nickname)}&game=${FACEIT_GAME}`;
  const result = await getJson(http, apiKey, url);
  if (!result.ok) return result;
  const body = result.value;
  const id = typeof body === "object" && body !== null ? (body as { player_id?: unknown }).player_id : undefined;
  return typeof id === "string" && isPlayerId(id) ? { ok: true, value: id } : { ok: false, failure: "unavailable" };
}

/** `GET /players/{id}/games/cs2/stats?limit=100` -> the raw `items[]`, newest first. */
export async function fetchMatchItems(http: typeof fetch, apiKey: string, playerId: string): Promise<FaceitCall<unknown[]>> {
  const url = `${FACEIT_BASE_URL}/players/${encodeURIComponent(playerId)}/games/${FACEIT_GAME}/stats?limit=${MATCH_LIMIT}`;
  const result = await getJson(http, apiKey, url);
  if (!result.ok) return result;
  const body = result.value;
  const items = typeof body === "object" && body !== null ? (body as { items?: unknown }).items : undefined;
  return Array.isArray(items) ? { ok: true, value: items } : { ok: false, failure: "unavailable" };
}
