import type { Deps } from "@/lib/deps";
import { requireUser } from "@/lib/auth/tokens";
import { ApiError, ok } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";

const BASE_URL = "https://open.faceit.com/data/v4";
const PLAYER_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const REGION = /^[a-z0-9_-]{2,12}$/i;

function upstreamPath(path: string[], query: URLSearchParams): string {
  if (path.length === 1 && path[0] === "players") {
    const nickname = query.get("nickname")?.trim();
    if (!nickname || nickname.length > 40) throw new ApiError("validation_failed", "Invalid Faceit nickname", 422);
    return `/players?nickname=${encodeURIComponent(nickname)}&game=cs2`;
  }

  if (path.length === 3 && path[0] === "players" && PLAYER_ID.test(path[1]) && path[2] === "stats") {
    return `/players/${path[1]}/stats/cs2`;
  }

  if (path.length === 5 && path[0] === "players" && PLAYER_ID.test(path[1]) &&
      path[2] === "games" && path[3] === "cs2" && path[4] === "stats") {
    return `/players/${path[1]}/games/cs2/stats?limit=100`;
  }

  if (path.length === 7 && path[0] === "rankings" && path[1] === "games" && path[2] === "cs2" &&
      path[3] === "regions" && REGION.test(path[4]) && path[5] === "players" && PLAYER_ID.test(path[6])) {
    return `/rankings/games/cs2/regions/${path[4]}/players/${path[6]}?limit=1`;
  }

  throw new ApiError("not_found", "Faceit endpoint not found", 404);
}

/** Authenticated, fixed-destination proxy for the small set of Faceit reads used by the mobile app. */
export async function proxyFaceit(req: Request, deps: Deps, path: string[]): Promise<Response> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, `faceit:${userId}`, 120, 60_000);

  const apiKey = process.env.FACEIT_API_KEY?.trim();
  if (!apiKey) throw new ApiError("internal", "Faceit is not configured on the server", 503);

  const requestUrl = new URL(req.url);
  const target = upstreamPath(path, requestUrl.searchParams);
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${target}`, {
      headers: { Authorization: `Bearer ${apiKey}`, Accept: "application/json" },
      signal: AbortSignal.timeout(3_000),
      redirect: "error",
    });
  } catch {
    throw new ApiError("internal", "Faceit is temporarily unavailable", 502);
  }

  if (response.status === 404) throw new ApiError("not_found", "Faceit player not found", 404);
  if (response.status === 429) throw new ApiError("rate_limited", "Faceit is temporarily rate limited", 429);
  if (!response.ok) throw new ApiError("internal", "Faceit is temporarily unavailable", 502);

  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new ApiError("internal", "Faceit returned an unreadable response", 502);
  }
  return ok(data);
}
