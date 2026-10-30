import { z } from "zod";
import { sendCode } from "@/lib/auth/codes";
import { hashPassword, verifyPassword } from "@/lib/auth/passwords";
import { issueSession } from "@/lib/auth/tokens";
import { normalizeEmail } from "@/lib/auth/validation";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import { findCredentials, isDemoEmail, userJson, type CredentialsRow } from "@/lib/users";

const LoginBody = z.object({ email: z.string(), password: z.string() });

let dummyHash: Promise<string> | undefined;

/** A fixed argon2id hash, computed once, so unknown emails cost as much as wrong passwords. */
function getDummyHash(): Promise<string> {
  dummyHash ??= hashPassword("pulse-timing-equalizer-0");
  return dummyHash;
}

function invalidCredentials(): ApiError {
  return new ApiError("invalid_credentials", "Invalid email or password.", 401);
}

/**
 * Checks an email and password and returns the confirmed account; the one sign-in rule for the app and the staff
 * website. Wrong credentials, unknown and demo emails all answer `401 invalid_credentials`; an unconfirmed email
 * gets a fresh code and `403 email_not_verified`.
 */
export async function authenticate(deps: Deps, rawEmail: string, password: string): Promise<CredentialsRow> {
  // Demo addresses are never server accounts: they behave like any unknown email.
  const email = normalizeEmail(rawEmail);
  const user = isDemoEmail(email) ? null : await findCredentials(deps, email);
  const passwordOk = await verifyPassword(user?.password_hash ?? (await getDummyHash()), password);
  if (!user || !passwordOk) throw invalidCredentials();

  if (!user.email_verified_at) {
    try {
      await sendCode(deps, user, "verify");
    } catch (e) {
      // The user can request another code later; do not leak a 429 here.
      if (!(e instanceof ApiError && e.code === "rate_limited")) throw e;
    }
    throw new ApiError("email_not_verified", "Email is not verified.", 403);
  }

  return user;
}

export async function loginHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, LoginBody);
  const user = await authenticate(deps, body.email, body.password);
  const tokens = await issueSession(deps, user.id);
  return ok({ user: await userJson(deps, user.id), ...tokens });
}
