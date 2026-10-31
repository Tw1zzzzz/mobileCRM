import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";

/**
 * Fixed-window limiter keyed by `floor(now / windowMs)`. The increment is a
 * single upsert, so concurrent requests are counted atomically.
 */
export async function rateLimit(
  deps: Deps,
  key: string,
  limit: number,
  windowMs: number,
): Promise<void> {
  const now = deps.now().getTime();
  const windowStart = new Date(Math.floor(now / windowMs) * windowMs);
  const [row] = await deps.sql<{ count: number }[]>`
    insert into rate_limits (key, window_start, count)
    values (${key}, ${windowStart}, 1)
    on conflict (key, window_start) do update set count = rate_limits.count + 1
    returning count`;
  if (row.count > limit) {
    throw rateLimited();
  }
}

function rateLimited(): ApiError {
  return new ApiError("rate_limited", "Too many requests. Try again in a few minutes.", 429);
}

/**
 * Throws `rate_limited` when `key` has already used up `limit` in the current window, without counting this call.
 * Pairs with {@link rateLimit}, which does the counting.
 */
export async function assertUnderLimit(
  deps: Deps,
  key: string,
  limit: number,
  windowMs: number,
): Promise<void> {
  const now = deps.now().getTime();
  const windowStart = new Date(Math.floor(now / windowMs) * windowMs);
  const [row] = await deps.sql<{ count: number }[]>`
    select count from rate_limits where key = ${key} and window_start = ${windowStart}`;
  if (row && row.count >= limit) throw rateLimited();
}

/** First `x-forwarded-for` entry, else `x-real-ip`, else "unknown". */
export function clientIp(req: { headers: { get(name: string): string | null } }): string {
  const forwarded = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (forwarded) return forwarded;
  return req.headers.get("x-real-ip")?.trim() || "unknown";
}
