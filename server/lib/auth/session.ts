import { z } from "zod";
import { revokeRefresh, rotateRefresh } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ok, parseBody } from "@/lib/http";
import { clientIp, rateLimit } from "@/lib/rateLimit";

const TokenBody = z.object({ refresh_token: z.string() });

export async function refreshHandler(req: Request, deps: Deps): Promise<Response> {
  // Its own bucket: phones refresh in the background and must not be locked out by sign-in attempts from the same IP.
  await rateLimit(deps, "refresh:" + clientIp(req), 60, 60_000);
  const body = await parseBody(req, TokenBody);
  const { tokens } = await rotateRefresh(deps, body.refresh_token);
  return ok(tokens);
}

export async function logoutHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, TokenBody);
  await revokeRefresh(deps, body.refresh_token);
  return ok({});
}
