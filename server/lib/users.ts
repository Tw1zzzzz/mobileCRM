import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";

export type Role = "soloPlayer" | "teamPlayer" | "staff";

export interface UserJson {
  id: string;
  nickname: string;
  email: string;
  role: Role;
  tariff: string;
  /** Team name. */
  team: string | null;
  team_id: string | null;
  /** Player code: staff of the team only. */
  invite_code: string | null;
  /** Staff code: staff of the team only. */
  staff_code: string | null;
  streak_days: number;
  faceit_url: string | null;
}

export interface UserRow {
  id: string;
  email: string;
  email_verified_at: Date | null;
}

/** These stay local in the app and are never accounts on the server. */
const DEMO_EMAILS: ReadonlySet<string> = new Set([
  "player@pulse.app",
  "solo@pulse.app",
  "staff@pulse.app",
]);

export function isDemoEmail(normalizedEmail: string): boolean {
  return DEMO_EMAILS.has(normalizedEmail);
}

export async function findUserByEmail(deps: Deps, normalizedEmail: string): Promise<UserRow | null> {
  const [row] = await deps.sql<UserRow[]>`
    select id, email, email_verified_at from users where email = ${normalizedEmail}`;
  return row ?? null;
}

interface UserJsonRow {
  id: string;
  nickname: string;
  email: string;
  role: Role;
  tariff: string;
  team_id: string | null;
  faceit_url: string | null;
  team_name: string | null;
  invite_code: string | null;
  staff_code: string | null;
}

/** The public shape of a user; never exposes the password hash or other columns. */
export async function userJson(deps: Deps, userId: string): Promise<UserJson> {
  const [row] = await deps.sql<UserJsonRow[]>`
    select u.id, u.nickname, u.email, u.role, u.tariff, u.team_id, u.faceit_url,
           t.name as team_name, t.invite_code, t.staff_code
    from users u left join teams t on t.id = u.team_id
    where u.id = ${userId}`;
  if (!row) throw new ApiError("unauthorized", "User not found", 401);
  return {
    id: row.id,
    nickname: row.nickname,
    email: row.email,
    role: row.role,
    tariff: row.tariff,
    team: row.team_name,
    team_id: row.team_id,
    invite_code: row.role === "staff" ? row.invite_code : null,
    staff_code: row.role === "staff" ? row.staff_code : null,
    streak_days: 0,
    faceit_url: row.faceit_url,
  };
}

export interface CredentialsRow extends UserRow {
  password_hash: string;
}

/** Login-only lookup: the one place besides registration that reads `password_hash`. */
export async function findCredentials(deps: Deps, normalizedEmail: string): Promise<CredentialsRow | null> {
  const [row] = await deps.sql<CredentialsRow[]>`
    select id, email, email_verified_at, password_hash from users where email = ${normalizedEmail}`;
  return row ?? null;
}
