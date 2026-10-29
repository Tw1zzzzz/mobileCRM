import { randomInt } from "node:crypto";
import type { Sql, TransactionSql } from "postgres";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { freeCode, type CodeKind, type RandomIndex } from "@/lib/teams";
import type { Role } from "@/lib/users";

const MAX_ROTATE_TRIES = 5;

export interface TeamMember {
  id: string;
  nickname: string;
  role: Role;
  /** When the account was created: a member joins a team when they register. */
  joined_at: Date;
}

const notFound = () => new ApiError("not_found", "No such team member.", 404);

function isUniqueViolation(e: unknown): boolean {
  return (e as { code?: string })?.code === "23505";
}

/**
 * Replaces one of the team's codes; the old code stops working at once. Who may do this is the caller's
 * business. Returns the new code.
 */
export async function rotateCode(
  deps: Deps,
  teamId: string,
  kind: CodeKind,
  random: RandomIndex = randomInt,
): Promise<string> {
  for (let attempt = 0; attempt < MAX_ROTATE_TRIES; attempt++) {
    const code = await freeCode(deps.sql, [], random);
    try {
      const rows =
        kind === "player"
          ? await deps.sql`update teams set invite_code = ${code} where id = ${teamId} returning id`
          : await deps.sql`update teams set staff_code = ${code} where id = ${teamId} returning id`;
      if (rows.length === 0) throw new ApiError("not_found", "No such team.", 404);
      return code;
    } catch (e) {
      if (!isUniqueViolation(e)) throw e; // lost a race for this code: draw another
    }
  }
  throw new ApiError("internal", "Could not allocate a team code", 500);
}

/** Members who have confirmed their email, longest-standing first. */
export async function teamMembers(deps: Deps, teamId: string): Promise<TeamMember[]> {
  return deps.sql<TeamMember[]>`
    select id, nickname, role, created_at as joined_at
    from users
    where team_id = ${teamId} and email_verified_at is not null
    order by created_at, id`;
}

/** The longest-standing staff member (email confirmed) of the team other than `exceptUserId`. */
export async function otherStaffId(
  sql: Sql | TransactionSql,
  teamId: string,
  exceptUserId: string,
): Promise<string | null> {
  const [row] = await sql<{ id: string }[]>`
    select id from users
    where team_id = ${teamId} and role = 'staff' and email_verified_at is not null and id <> ${exceptUserId}
    order by created_at, id
    limit 1`;
  return row?.id ?? null;
}

/**
 * Takes `memberId` out of the team; they become a solo player. The actor must be staff of the same team, and
 * a member of another team looks like nobody at all (404). The last staff member cannot be removed; when the
 * owner goes, ownership passes to the longest-standing other staff member.
 */
export async function removeMember(
  deps: Deps,
  teamId: string,
  actorId: string,
  memberId: string,
): Promise<void> {
  await deps.sql.begin(async (tx) => {
    const [team] = await tx<{ owner_id: string | null }[]>`select owner_id from teams where id = ${teamId} for update`;
    if (!team) throw notFound();
    const [actor] = await tx`select 1 from users where id = ${actorId} and team_id = ${teamId} and role = 'staff'`;
    if (!actor) throw notFound();
    const [member] = await tx<{ role: Role }[]>`select role from users where id = ${memberId} and team_id = ${teamId}`;
    if (!member) throw notFound();

    if (member.role === "staff") {
      const successor = await otherStaffId(tx, teamId, memberId);
      if (!successor) {
        throw new ApiError("validation_failed", "The last staff member cannot leave the team.", 422);
      }
      if (team.owner_id === memberId) await tx`update teams set owner_id = ${successor} where id = ${teamId}`;
    }
    await tx`
      update users set role = 'soloPlayer', team_id = null, updated_at = ${deps.now()}
      where id = ${memberId}`;
  });
}

/** Deletes a team; everyone else in it goes back to being a solo player. */
export async function dismantleTeam(
  tx: TransactionSql,
  deps: Deps,
  teamId: string,
  exceptUserId: string,
): Promise<void> {
  await tx`
    update users set role = 'soloPlayer', team_id = null, updated_at = ${deps.now()}
    where team_id = ${teamId} and id <> ${exceptUserId}`;
  await tx`delete from teams where id = ${teamId}`;
}
