import { z } from "zod";
import { checkCode, sendCode } from "@/lib/auth/codes";
import { hashPassword } from "@/lib/auth/passwords";
import { issueSession, revokeAll } from "@/lib/auth/tokens";
import { isStrongPassword, normalizeEmail } from "@/lib/auth/validation";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import { findUserByEmail, isDemoEmail, userJson, type UserRow } from "@/lib/users";

const ResetBody = z.object({ email: z.string() });
const ConfirmBody = z.object({ email: z.string(), code: z.string(), new_password: z.string() });

async function findAccount(deps: Deps, email: string): Promise<UserRow | null> {
  return isDemoEmail(email) ? null : findUserByEmail(deps, email);
}

/** Always 200 `{}`: whether the address has an account is never revealed. */
export async function passwordResetHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, ResetBody);

  const user = await findAccount(deps, normalizeEmail(body.email));
  if (user) {
    try {
      await sendCode(deps, user, "reset");
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "rate_limited")) throw e;
    }
  }
  return ok({});
}

export async function passwordResetConfirmHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, ConfirmBody);

  // Checked first and for every email, so a weak password neither burns the code nor reveals the account.
  if (!isStrongPassword(body.new_password)) {
    throw new ApiError("weak_password", "Password must be at least 8 characters with letters and digits.", 422);
  }
  const user = await findAccount(deps, normalizeEmail(body.email));
  if (!user) throw new ApiError("invalid_code", "Invalid code.", 422);

  await checkCode(deps, user.id, "reset", body.code.trim());
  const passwordHash = await hashPassword(body.new_password);
  const now = deps.now();
  await deps.sql`
    update users set password_hash = ${passwordHash}, updated_at = ${now},
      email_verified_at = coalesce(email_verified_at, ${now})
    where id = ${user.id}`;
  await revokeAll(deps, user.id);

  const tokens = await issueSession(deps, user.id);
  return ok({ user: await userJson(deps, user.id), ...tokens });
}
