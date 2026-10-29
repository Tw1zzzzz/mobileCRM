import { randomUUID } from "node:crypto";
import type { Sql } from "postgres";
import { z } from "zod";
import { sendCode } from "@/lib/auth/codes";
import { hashPassword } from "@/lib/auth/passwords";
import {
  isStrongPassword,
  isValidEmail,
  isValidFaceitUrl,
  normalizeEmail,
} from "@/lib/auth/validation";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { clientIp, rateLimit } from "@/lib/rateLimit";
import { dismantleTeam } from "@/lib/team/members";
import { createTeam, findTeamByCode, findTeamByStaffCode } from "@/lib/teams";
import { findUserByEmail, isDemoEmail, type Role } from "@/lib/users";

const TEAM_NAME_MAX = 60;

const RegisterBody = z.object({
  nickname: z.string().trim().min(1).max(40),
  email: z.string(),
  password: z.string(),
  role: z.enum(["soloPlayer", "teamPlayer", "staff"]),
  faceit_url: z.string().nullish(),
  team_code: z.string().nullish(),
  team_name: z.string().nullish(),
  staff_code: z.string().nullish(),
  accepted_terms: z.literal(true),
  accepted_privacy: z.literal(true),
  accepted_beta_terms: z.literal(true),
  beta_terms_version: z.string().trim().min(1).max(40),
});

interface NewAccount {
  email: string;
  nickname: string;
  passwordHash: string;
  role: Role;
  faceitUrl: string | null;
  /** Joined team (teamPlayer with a player code, staff with a staff code). */
  joinTeamId: string | null;
  /** Team to create or rename (staff without a staff code). */
  teamName: string | null;
  betaTermsVersion: string;
}

function validationFailed(message: string): ApiError {
  return new ApiError("validation_failed", message, 422);
}

export function parseFaceitUrl(raw: string | null | undefined): string | null {
  const trimmed = raw?.trim();
  if (!trimmed) return null;
  if (!isValidFaceitUrl(trimmed)) throw validationFailed("faceit_url: must be a faceit.com link");
  return trimmed;
}

function parseTeamName(raw: string | null | undefined): string {
  const name = raw?.trim() ?? "";
  if (name.length < 1 || name.length > TEAM_NAME_MAX) {
    throw validationFailed(`team_name: required, 1-${TEAM_NAME_MAX} characters`);
  }
  return name;
}

function isEmailConflict(e: unknown): boolean {
  const err = e as { code?: string; constraint_name?: string };
  return err?.code === "23505" && err.constraint_name === "users_email_key";
}

/** Inserts or overwrites the account and its team in one transaction; returns the user. */
async function saveAccount(deps: Deps, account: NewAccount): Promise<{ id: string; email: string }> {
  const now = deps.now();
  try {
    return await deps.sql.begin(async (tx) => {
      const txDeps: Deps = { ...deps, sql: tx as unknown as Sql };
      const [existing] = await tx<{ id: string; email_verified_at: Date | null }[]>`
        select id, email_verified_at from users where email = ${account.email} for update`;
      if (existing?.email_verified_at) throw emailTaken();

      const [owned] = existing
        ? await tx<{ id: string }[]>`select id from teams where owner_id = ${existing.id}`
        : [];
      const userId = existing?.id ?? randomUUID();
      const keptTeamId =
        account.role === "staff" && account.joinTeamId === null ? (owned?.id ?? null) : account.joinTeamId;

      if (existing) {
        await tx`
          update users set nickname = ${account.nickname}, password_hash = ${account.passwordHash},
            role = ${account.role}, team_id = ${keptTeamId}, faceit_url = ${account.faceitUrl},
            accepted_terms_at = ${now}, accepted_privacy_at = ${now},
            beta_terms_version = ${account.betaTermsVersion}, beta_terms_accepted_at = ${now},
            updated_at = ${now}
          where id = ${userId}`;
      } else {
        await tx`
          insert into users (id, email, nickname, password_hash, role, team_id, faceit_url,
            accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at,
            created_at, updated_at)
          values (${userId}, ${account.email}, ${account.nickname}, ${account.passwordHash},
            ${account.role}, ${keptTeamId}, ${account.faceitUrl}, ${now}, ${now},
            ${account.betaTermsVersion}, ${now}, ${now}, ${now})`;
      }

      if (account.teamName !== null) {
        if (owned) {
          await tx`update teams set name = ${account.teamName} where id = ${owned.id}`;
        } else {
          const team = await createTeam(txDeps, account.teamName, userId);
          await tx`update users set team_id = ${team.id} where id = ${userId}`;
        }
      } else if (owned && owned.id !== account.joinTeamId) {
        // No longer this team's owner: the old team goes away (users.team_id already moved off it).
        await dismantleTeam(tx, deps, owned.id, userId);
      }
      return { id: userId, email: account.email };
    });
  } catch (e) {
    if (isEmailConflict(e)) throw emailTaken();
    throw e;
  }
}

function emailTaken(): ApiError {
  return new ApiError("email_taken", "This email is already registered.", 409);
}

export async function registerHandler(req: Request, deps: Deps): Promise<Response> {
  await rateLimit(deps, "auth:" + clientIp(req), 10, 60_000);
  const body = await parseBody(req, RegisterBody);

  const email = normalizeEmail(body.email);
  if (!isValidEmail(email)) throw new ApiError("invalid_email", "Invalid email address.", 422);
  if (!isStrongPassword(body.password)) {
    throw new ApiError("weak_password", "Password must be at least 8 characters with letters and digits.", 422);
  }
  const faceitUrl = parseFaceitUrl(body.faceit_url);
  const staffCode = body.role === "staff" ? body.staff_code?.trim() || null : null;
  const teamName = body.role === "staff" && !staffCode ? parseTeamName(body.team_name) : null;
  if (staffCode && body.team_name?.trim()) {
    throw validationFailed("staff: give either team_name or staff_code, not both");
  }

  if (isDemoEmail(email) || (await findUserByEmail(deps, email))?.email_verified_at) {
    throw emailTaken();
  }

  let joinTeamId: string | null = null;
  if (body.role === "teamPlayer") {
    const code = body.team_code?.trim();
    if (!code) throw validationFailed("team_code: required for teamPlayer");
    const team = await findTeamByCode(deps, code);
    if (!team) throw new ApiError("team_not_found", "No team with this code.", 404);
    joinTeamId = team.id;
  }

  if (staffCode) {
    const team = await findTeamByStaffCode(deps, staffCode);
    if (!team) throw new ApiError("team_not_found", "No team with this code.", 404);
    joinTeamId = team.id;
  }

  const user = await saveAccount(deps, {
    email,
    nickname: body.nickname,
    passwordHash: await hashPassword(body.password),
    role: body.role,
    faceitUrl,
    joinTeamId,
    teamName,
    betaTermsVersion: body.beta_terms_version,
  });
  await sendCode(deps, user, "verify");
  return ok({ pending_verification: true, email }, 201);
}
