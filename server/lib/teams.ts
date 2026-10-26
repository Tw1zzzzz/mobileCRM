import { randomInt } from "node:crypto";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";

const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const INVITE_LENGTH = 6;
const MAX_INVITE_TRIES = 5;

export function normalizeInviteCode(code: string): string {
  return code.trim().toUpperCase();
}

function generateInviteCode(): string {
  let code = "";
  for (let i = 0; i < INVITE_LENGTH; i++) {
    code += INVITE_ALPHABET[randomInt(INVITE_ALPHABET.length)];
  }
  return code;
}

/**
 * Creates a team owned by `ownerId` with a fresh unique invite code. A code
 * collision is skipped with `on conflict do nothing`, so this also works
 * inside a transaction (a failed insert would abort it).
 */
export async function createTeam(
  deps: Deps,
  name: string,
  ownerId: string,
): Promise<{ id: string; invite_code: string }> {
  for (let attempt = 0; attempt < MAX_INVITE_TRIES; attempt++) {
    const inviteCode = generateInviteCode();
    const [row] = await deps.sql<{ id: string; invite_code: string }[]>`
      insert into teams (name, invite_code, owner_id, created_at)
      values (${name}, ${inviteCode}, ${ownerId}, ${deps.now()})
      on conflict (invite_code) do nothing
      returning id, invite_code`;
    if (row) return row;
  }
  throw new ApiError("internal", "Could not allocate an invite code", 500);
}

/** Case-insensitive, whitespace-tolerant lookup by invite code. */
export async function findTeamByCode(
  deps: Deps,
  code: string,
): Promise<{ id: string; name: string } | null> {
  const [row] = await deps.sql<{ id: string; name: string }[]>`
    select id, name from teams where invite_code = ${normalizeInviteCode(code)}`;
  return row ?? null;
}
