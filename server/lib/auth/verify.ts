import { z } from "zod";
import { checkCode, sendCode } from "@/lib/auth/codes";
import { verifyPassword } from "@/lib/auth/passwords";
import { issueSession } from "@/lib/auth/tokens";
import { normalizeEmail } from "@/lib/auth/validation";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import { findCredentials, findUserByEmail, userJson } from "@/lib/users";

const VerifyBody = z.object({ email: z.string(), code: z.string(), password: z.string() });
const ResendBody = z.object({ email: z.string() });

function invalidCode(): ApiError {
  return new ApiError("invalid_code", "Invalid code.", 422);
}

export async function verifyEmailHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, VerifyBody);

  // Unknown and already-verified accounts answer exactly like a wrong code.
  const user = await findCredentials(deps, normalizeEmail(body.email));
  if (!user || user.email_verified_at) throw invalidCode();

  // The password of the account being confirmed: someone who re-registered this unverified address with their own
  // password cannot have it confirmed by the owner's code. A wrong one costs an attempt, like a wrong code.
  const passwordOk = await verifyPassword(user.password_hash, body.password);
  await checkCode(deps, user.id, "verify", body.code.trim(), passwordOk);
  const now = deps.now();
  await deps.sql`
    update users set email_verified_at = ${now}, updated_at = ${now}
    where id = ${user.id} and email_verified_at is null`;

  const tokens = await issueSession(deps, user.id);
  return ok({ user: await userJson(deps, user.id), ...tokens });
}

export async function resendCodeHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, ResendBody);

  const user = await findUserByEmail(deps, normalizeEmail(body.email));
  if (user && !user.email_verified_at) await sendCode(deps, user, "verify");
  return ok({});
}
