import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { COLLECTIONS, type SyncCollection } from "@/lib/sync";
import { teamMembers } from "@/lib/team/members";
import { mindsetScores } from "@/lib/staff/mindset";

/**
 * The only place the staff website reads player data from. A staff member sees players of their own team
 * (role `teamPlayer`, email confirmed) and nothing else; `body-profile` is never readable, `lineups` only the
 * entries shared with the team, and `player-survey` only scores, never answers.
 */

export interface StaffContext {
  userId: string;
  teamId: string;
  teamName: string;
}

export interface TeamPlayer {
  id: string;
  nickname: string;
  /** Latest time any of the player's documents was synced; null when nothing was synced yet. */
  lastActivity: Date | null;
}

/** Collections staff may ask for: everything synced except `body-profile`. */
export type Collection = Exclude<SyncCollection, "body-profile">;

const BODY_PROFILE: SyncCollection = "body-profile";

const forbidden = () => new ApiError("forbidden", "Staff of a team only.", 403);
const playerNotFound = () => new ApiError("not_found", "No such player in your team.", 404);

/** Staff of a team with a confirmed email; anyone else who is signed in gets 403. */
export async function requireStaff(deps: Deps, userId: string): Promise<StaffContext> {
  const [row] = await deps.sql<{ team_id: string | null; team_name: string | null }[]>`
    select u.team_id, t.name as team_name
    from users u left join teams t on t.id = u.team_id
    where u.id = ${userId}`;
  if (!row) throw new ApiError("unauthorized", "Not authorized", 401);
  if (row.team_id === null || row.team_name === null) throw forbidden();
  const members = await teamMembers(deps, row.team_id);
  if (!members.some((m) => m.id === userId && m.role === "staff")) throw forbidden();
  return { userId, teamId: row.team_id, teamName: row.team_name };
}

async function rosterPlayers(deps: Deps, teamId: string): Promise<{ id: string; nickname: string }[]> {
  const members = await teamMembers(deps, teamId);
  return members.filter((m) => m.role === "teamPlayer").map((m) => ({ id: m.id, nickname: m.nickname }));
}

/** The team's players, longest-standing first, with the time of their latest sync. */
export async function teamPlayers(deps: Deps, ctx: StaffContext): Promise<TeamPlayer[]> {
  const players = await rosterPlayers(deps, ctx.teamId);
  if (players.length === 0) return [];
  const rows = await deps.sql<{ user_id: string; last: Date }[]>`
    select user_id, max(updated_at) as last
    from sync_documents
    where user_id in ${deps.sql(players.map((p) => p.id))}
    group by user_id`;
  const last = new Map(rows.map((r) => [r.user_id, r.last]));
  return players.map((p) => ({ id: p.id, nickname: p.nickname, lastActivity: last.get(p.id) ?? null }));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Only entries the author shared with the team; diary notes stay private. */
function sharedLineups(document: unknown): unknown[] {
  if (!Array.isArray(document)) return [];
  return document.filter((entry) => isRecord(entry) && entry.visibility === "team");
}

/** The questionnaire and the scores of each test run; the answers themselves are dropped. */
function surveyWithScores(document: unknown): { survey: unknown; mindset: unknown[] } {
  const doc = isRecord(document) ? document : {};
  const runs = Array.isArray(doc.mindset) ? doc.mindset : [];
  const mindset = runs.filter(isRecord).map((run) => ({
    id: run.id,
    date: run.date,
    scores: mindsetScores(run.answers),
  }));
  return { survey: doc.survey ?? null, mindset };
}

function filtered(collection: Collection, document: unknown): unknown {
  switch (collection) {
    case "lineups":
      return sharedLineups(document);
    case "player-survey":
      return surveyWithScores(document);
    default:
      return document;
  }
}

function requireReadable(collections: readonly string[]): asserts collections is readonly Collection[] {
  for (const name of collections) {
    if (name === BODY_PROFILE) throw new ApiError("forbidden", "This data is not available to staff.", 403);
    if (!(COLLECTIONS as readonly string[]).includes(name)) {
      throw new ApiError("validation_failed", `Unknown collection: ${name}`, 422);
    }
  }
}

/**
 * The stored documents of one player of the caller's team, after the privacy filter. Collections the player
 * never synced are absent from the result. Anyone who is not a player of this team is `404`.
 */
export async function playerDocuments(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  collections: readonly Collection[],
): Promise<Partial<Record<Collection, unknown>>> {
  requireReadable(collections);
  const players = await rosterPlayers(deps, ctx.teamId);
  if (!players.some((p) => p.id === playerId)) throw playerNotFound();
  if (collections.length === 0) return {};
  const rows = await deps.sql<{ collection: string; document: unknown }[]>`
    select collection, document from sync_documents
    where user_id = ${playerId} and collection in ${deps.sql([...collections])}`;
  const result: Partial<Record<Collection, unknown>> = {};
  for (const row of rows) {
    const collection = row.collection as Collection;
    result[collection] = filtered(collection, row.document);
  }
  return result;
}
