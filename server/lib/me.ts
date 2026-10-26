import { z } from "zod";
import { parseFaceitUrl } from "@/lib/auth/register";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ok, parseBody } from "@/lib/http";
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
 * Deletes the account. Refresh tokens and email codes cascade. An owned team is
 * deleted too and its other members go back to being solo players.
 */
export async function deleteMe(req: Request, deps: Deps): Promise<Response> {
  const userId = await requireUser(deps, req);
  await deps.sql.begin(async (tx) => {
    // Locking the team rows keeps a concurrent join from slipping in mid-delete.
    const owned = await tx<{ id: string }[]>`select id from teams where owner_id = ${userId} for update`;
    if (owned.length > 0) {
      await tx`
        update users set role = 'soloPlayer', team_id = null, updated_at = ${deps.now()}
        where team_id in ${tx(owned.map((t) => t.id))} and id <> ${userId}`;
      await tx`delete from teams where owner_id = ${userId}`;
    }
    await tx`delete from users where id = ${userId}`;
  });
  return ok({});
}
