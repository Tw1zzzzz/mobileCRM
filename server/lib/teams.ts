import { randomInt } from "node:crypto";
import type { Sql, TransactionSql } from "postgres";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 6;
const MAX_CODE_TRIES = 5;

/** Picks an integer in `[0, max)`; injectable so tests can force collisions. */
export type RandomIndex = (max: number) => number;

type Queryable = Sql | TransactionSql;

export type CodeKind = "player" | "staff";

export function normalizeInviteCode(code: string): string {
  return code.trim().toUpperCase();
}

function generateCode(random: RandomIndex): string {
  let code = "";
  for (let i = 0; i < CODE_LENGTH; i++) {
    code += CODE_ALPHABET[random(CODE_ALPHABET.length)];
  }
  return code;
}

/**
 * A fresh code that no team holds in either column and that is not in `alsoTaken`. Two concurrent requests
 * could still draw the same code; the unique constraints turn that into a retry by the caller.
 */
export async function freeCode(
  sql: Queryable,
  alsoTaken: readonly string[] = [],
  random: RandomIndex = randomInt,
): Promise<string> {
  for (let attempt = 0; attempt < MAX_CODE_TRIES; attempt++) {
    const code = generateCode(random);
    if (alsoTaken.includes(code)) continue;
    const [clash] = await sql`select 1 from teams where invite_code = ${code} or staff_code = ${code}`;
    if (!clash) return code;
  }
  throw new ApiError("internal", "Could not allocate a team code", 500);
}

/**
 * Creates a team owned by `ownerId` with a fresh player code and staff code. A lost race on either code is
 * skipped with `on conflict do nothing`, so this also works inside a transaction (a failed insert would abort it).
 */
export async function createTeam(
  deps: Deps,
  name: string,
  ownerId: string,
  random: RandomIndex = randomInt,
): Promise<{ id: string; invite_code: string; staff_code: string }> {
  for (let attempt = 0; attempt < MAX_CODE_TRIES; attempt++) {
    const inviteCode = await freeCode(deps.sql, [], random);
    const staffCode = await freeCode(deps.sql, [inviteCode], random);
    const [row] = await deps.sql<{ id: string; invite_code: string; staff_code: string }[]>`
      insert into teams (name, invite_code, staff_code, owner_id, created_at)
      values (${name}, ${inviteCode}, ${staffCode}, ${ownerId}, ${deps.now()})
      on conflict do nothing
      returning id, invite_code, staff_code`;
    if (row) return row;
  }
  throw new ApiError("internal", "Could not allocate a team code", 500);
}

/** Case-insensitive, whitespace-tolerant lookup by player code. */
export async function findTeamByCode(
  deps: Deps,
  code: string,
): Promise<{ id: string; name: string } | null> {
  const [row] = await deps.sql<{ id: string; name: string }[]>`
    select id, name from teams where invite_code = ${normalizeInviteCode(code)}`;
  return row ?? null;
}

/** Same as `findTeamByCode`, for the staff code. A player code never matches. */
export async function findTeamByStaffCode(
  deps: Deps,
  code: string,
): Promise<{ id: string; name: string } | null> {
  const [row] = await deps.sql<{ id: string; name: string }[]>`
    select id, name from teams where staff_code = ${normalizeInviteCode(code)}`;
  return row ?? null;
}

/** The code players enter to join the team (`invite_code`). */
export async function playerCodeOf(deps: Deps, teamId: string): Promise<string> {
  const [row] = await deps.sql<{ invite_code: string }[]>`select invite_code from teams where id = ${teamId}`;
  if (!row) throw new ApiError("not_found", "No such team.", 404);
  return row.invite_code;
}

/** Both codes of a team: the one players enter and the one staff enter. Staff of the team only. */
export async function teamCodes(deps: Deps, teamId: string): Promise<{ player: string; staff: string }> {
  const [row] = await deps.sql<{ invite_code: string; staff_code: string }[]>`
    select invite_code, staff_code from teams where id = ${teamId}`;
  if (!row) throw new ApiError("not_found", "No such team.", 404);
  return { player: row.invite_code, staff: row.staff_code };
}
