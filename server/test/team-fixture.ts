import { issueSession } from "../lib/auth/tokens";
import type { TestDeps } from "./helpers";

export type MemberRole = "soloPlayer" | "teamPlayer" | "staff";

let counter = 0;

/** A user row; `verified: false` leaves the e-mail unconfirmed. */
export async function addMember(
  deps: TestDeps, nickname: string, role: MemberRole, teamId: string | null, verified = true,
): Promise<string> {
  counter += 1;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`m${counter}@team-api.gg`}, ${nickname}, 'x', ${role}, ${teamId}, ${verified ? now : null},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  deps.advance(1_000);
  return row.id;
}

export async function bearer(deps: TestDeps, userId: string): Promise<string> {
  return (await issueSession(deps, userId)).access_token;
}

/** A JSON request with a bearer token (none when `token` is undefined). */
export function jsonRequest(url: string, method: string, token: string | undefined, body?: unknown): Request {
  const headers: Record<string, string> = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  return new Request(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}
