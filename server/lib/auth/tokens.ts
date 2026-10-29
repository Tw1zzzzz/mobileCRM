import { createHash, randomBytes } from "node:crypto";
import { errors, jwtVerify, SignJWT } from "jose";
import type { Sql, TransactionSql } from "postgres";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";

export interface TokenPair {
  access_token: string;
  refresh_token: string;
}

const ACCESS_TTL_SECONDS = 15 * 60;
const REFRESH_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** How long a rotated-away token may be retried when its successor never reached the phone. */
const REUSE_GRACE_MS = 60 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Queryable = Sql | TransactionSql;

const unauthorized = () => new ApiError("unauthorized", "Not authorized", 401);

const sha256Hex = (value: string) => createHash("sha256").update(value).digest("hex");

async function signAccess(deps: Deps, userId: string): Promise<string> {
  const issuedAt = Math.floor(deps.now().getTime() / 1000);
  return new SignJWT({})
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(userId)
    .setIssuedAt(issuedAt)
    .setExpirationTime(issuedAt + ACCESS_TTL_SECONDS)
    .sign(deps.secret);
}

/** Inserts a fresh refresh token row and returns its id and plaintext value. */
async function insertRefresh(
  sql: Queryable,
  deps: Deps,
  userId: string,
): Promise<{ id: string; token: string }> {
  const token = randomBytes(32).toString("base64url");
  const now = deps.now();
  const expiresAt = new Date(now.getTime() + REFRESH_TTL_MS);
  const [row] = await sql<{ id: string }[]>`
    insert into refresh_tokens (user_id, token_hash, expires_at, created_at)
    values (${userId}, ${sha256Hex(token)}, ${expiresAt}, ${now})
    returning id`;
  return { id: row.id, token };
}

export async function issueSession(deps: Deps, userId: string): Promise<TokenPair> {
  const refresh = await insertRefresh(deps.sql, deps, userId);
  return { access_token: await signAccess(deps, userId), refresh_token: refresh.token };
}

interface RefreshRow {
  id: string;
  user_id: string;
  expires_at: Date;
  revoked_at: Date | null;
  replaced_by: string | null;
}

type RotateOutcome =
  | { kind: "rotated"; userId: string; refreshToken: string }
  | { kind: "replayed" }
  | { kind: "rejected" };

interface SuccessorRow {
  revoked_at: Date | null;
  created_at: Date;
}

/**
 * A rotated-away token comes back while its successor was never used and is at
 * most an hour old: the answer with the successor was lost on the way to the
 * phone. The token itself keeps pointing at that (now revoked) successor, so a
 * second retry is treated as a leak.
 */
async function isLostResponseRetry(tx: TransactionSql, successorId: string, now: Date): Promise<boolean> {
  const [successor] = await tx<SuccessorRow[]>`
    select revoked_at, created_at from refresh_tokens where id = ${successorId} for update`;
  if (!successor || successor.revoked_at) return false;
  return now.getTime() - successor.created_at.getTime() <= REUSE_GRACE_MS;
}

/**
 * Exchanges a refresh token for a new pair. Presenting a token that was already
 * rotated away means it leaked, so every session of that user is revoked —
 * except for one retry of a lost answer ([isLostResponseRetry]), which revokes
 * the undelivered successor and issues a fresh pair instead.
 */
export async function rotateRefresh(
  deps: Deps,
  refresh: string,
): Promise<{ userId: string; tokens: TokenPair }> {
  const now = deps.now();
  const outcome = await deps.sql.begin(async (tx): Promise<RotateOutcome> => {
    const [row] = await tx<RefreshRow[]>`
      select id, user_id, expires_at, revoked_at, replaced_by
      from refresh_tokens where token_hash = ${sha256Hex(refresh)} for update`;
    if (!row) return { kind: "rejected" };
    if (row.revoked_at) {
      if (!row.replaced_by) return { kind: "rejected" };
      if (await isLostResponseRetry(tx, row.replaced_by, now)) {
        await tx`update refresh_tokens set revoked_at = ${now} where id = ${row.replaced_by}`;
        const retry = await insertRefresh(tx, deps, row.user_id);
        return { kind: "rotated", userId: row.user_id, refreshToken: retry.token };
      }
      await tx`
        update refresh_tokens set revoked_at = ${now}
        where user_id = ${row.user_id} and revoked_at is null`;
      return { kind: "replayed" };
    }
    if (row.expires_at.getTime() <= now.getTime()) return { kind: "rejected" };
    const next = await insertRefresh(tx, deps, row.user_id);
    await tx`
      update refresh_tokens set revoked_at = ${now}, replaced_by = ${next.id}
      where id = ${row.id}`;
    return { kind: "rotated", userId: row.user_id, refreshToken: next.token };
  });
  if (outcome.kind !== "rotated") throw unauthorized();
  return {
    userId: outcome.userId,
    tokens: {
      access_token: await signAccess(deps, outcome.userId),
      refresh_token: outcome.refreshToken,
    },
  };
}

/** Logs out one session. Unknown or already revoked tokens are ignored. */
export async function revokeRefresh(deps: Deps, refresh: string): Promise<void> {
  await deps.sql`
    update refresh_tokens set revoked_at = ${deps.now()}
    where token_hash = ${sha256Hex(refresh)} and revoked_at is null`;
}

export async function revokeAll(deps: Deps, userId: string): Promise<void> {
  await deps.sql`
    update refresh_tokens set revoked_at = ${deps.now()}
    where user_id = ${userId} and revoked_at is null`;
}

/** Resolves `Authorization: Bearer <jwt>` to an existing user's id. */
export async function requireUser(deps: Deps, req: Request): Promise<string> {
  const header = req.headers.get("authorization") ?? "";
  const match = /^Bearer\s+(\S+)$/i.exec(header);
  if (!match) throw unauthorized();
  let sub: string | undefined;
  try {
    const { payload } = await jwtVerify(match[1], deps.secret, {
      algorithms: ["HS256"],
      currentDate: deps.now(),
    });
    sub = payload.sub;
  } catch (e) {
    if (e instanceof errors.JWTExpired) {
      throw new ApiError("token_expired", "Access token expired", 401);
    }
    throw unauthorized();
  }
  if (!sub || !UUID.test(sub)) throw unauthorized();
  const [user] = await deps.sql<{ id: string }[]>`select id from users where id = ${sub}`;
  if (!user) throw unauthorized();
  return user.id;
}
