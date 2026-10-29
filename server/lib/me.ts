import { z } from "zod";
import { parseFaceitUrl } from "@/lib/auth/register";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ok, parseBody } from "@/lib/http";
import { dismantleTeam, otherStaffId } from "@/lib/team/members";
import { userJson } from "@/lib/users";

const PatchBody = z.object({
  nickname: z.string().trim().min(1).max(40).optional(),
  faceit_url: z.string().nullable().optional(),
});

export async function getMe(req: Request, deps: Deps): Promise<Response> {
  const userId = await requireUser(deps, req);
  return ok(await userJson(deps, userId));
}

export async function patchMe(req: Request, deps: Deps): Promise<Response> {
  const userId = await requireUser(deps, req);
  const body = await parseBody(req, PatchBody);
  const changes: { nickname?: string; faceit_url?: string | null; updated_at: Date } = {
    updated_at: deps.now(),
  };
  if (body.nickname !== undefined) changes.nickname = body.nickname;
  if (body.faceit_url !== undefined) changes.faceit_url = parseFaceitUrl(body.faceit_url);
  await deps.sql`update users set ${deps.sql(changes)} where id = ${userId}`;
  return ok(await userJson(deps, userId));
}

/**
 * Deletes the account. Refresh tokens and email codes cascade. When the user owns a team, another staff member
 * (the longest-standing one) takes it over; if there is none, the team is deleted and its members go back to
 * being solo players.
 */
export async function deleteMe(req: Request, deps: Deps): Promise<Response> {
  const userId = await requireUser(deps, req);
  await deps.sql.begin(async (tx) => {
    const [self] = await tx<{ team_id: string | null }[]>`select team_id from users where id = ${userId}`;
    // Locking the team row keeps a concurrent join, removal or deletion from slipping in mid-delete.
    const teams = await tx<{ id: string; owner_id: string | null }[]>`
      select id, owner_id from teams where owner_id = ${userId} or id = ${self?.team_id ?? null}::uuid for update`;
    for (const team of teams.filter((t) => t.owner_id === userId)) {
      const successor = await otherStaffId(tx, team.id, userId);
      if (successor) {
        await tx`update teams set owner_id = ${successor} where id = ${team.id}`;
      } else {
        await dismantleTeam(tx, deps, team.id, userId);
      }
    }
    await tx`delete from users where id = ${userId}`;
  });
  return ok({});
}
