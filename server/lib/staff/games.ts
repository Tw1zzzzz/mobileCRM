import type { Sql, TransactionSql } from "postgres";
import { z } from "zod";
import type { Deps } from "@/lib/deps";
import { ApiError } from "@/lib/http";
import { requireStaff, rosterMembers, type StaffContext } from "@/lib/staff/access";
import {
  gameTotals, type MapStats, type PistolResult, type RowStats, type StatTotals,
} from "@/lib/staff/games-math";
import { LINEUP_MAPS } from "@/lib/staff/lineups";

/**
 * Games the staff enter by hand (pracc / tournament). Every read and write is scoped to the caller's team; only
 * staff of that team write. A player line is either a roster player (the nickname is copied from the roster at
 * write time) or a stand-in typed by nickname; the nickname stays when the player later leaves the team or the app.
 */

export const GAME_KINDS = ["pracc", "tournament"] as const;
/** The maps of the lineups base plus the two the games accept in addition. */
export const GAME_MAPS = [...LINEUP_MAPS, "overpass", "vertigo"] as const;
export type GameKind = (typeof GAME_KINDS)[number];
export type GameMapName = (typeof GAME_MAPS)[number];

export const MAX_GAME_MAPS = 5;
export const MAX_ROWS_PER_MAP = 10;

type Queryable = Sql | TransactionSql;

// ---------------------------------------------------------------------------------------------------- input

/** One player line as the form sends it: a roster `playerId` OR a stand-in `nickname`, never both. */
export interface GameRowInput {
  playerId?: string;
  nickname?: string;
  kills: number;
  deaths: number;
  assists: number;
  adr: number;
  fk?: number | null;
  fd?: number | null;
  multikills?: number | null;
  clutches?: number | null;
  flashTime?: number | null;
}

export interface GameMapInput {
  map: GameMapName;
  ourScore: number;
  theirScore: number;
  pistol1: PistolResult;
  pistol2: PistolResult;
  rows: GameRowInput[];
}

export interface GameInput {
  /** Present: create the game under this id, or replace it when it already belongs to the team. */
  id?: string;
  kind: GameKind;
  /** `YYYY-MM-DD`. */
  playedOn: string;
  opponent?: string | null;
  maps: GameMapInput[];
}

const count = (max: number) => z.number().int().min(0).max(max);
const oneDecimal = (max: number) =>
  z.number().min(0).max(max).refine((v) => Math.abs(v * 10 - Math.round(v * 10)) < 1e-7, "at most one decimal place");
const optional = <T extends z.ZodType>(schema: T) => schema.nullish().transform((v) => v ?? null);

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/).refine((value) => {
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}, "not a calendar date");

const uuid = z.string().uuid().transform((value) => value.toLowerCase());

const RowSchema = z.object({
  playerId: uuid.optional(),
  nickname: z.string().trim().min(1).max(40).optional(),
  kills: count(99),
  deaths: count(99),
  assists: count(99),
  adr: oneDecimal(300),
  fk: optional(count(30)),
  fd: optional(count(30)),
  multikills: optional(oneDecimal(10)),
  clutches: optional(count(20)),
  flashTime: optional(oneDecimal(60)),
}).refine((row) => (row.playerId === undefined) !== (row.nickname === undefined), {
  message: "a line is either a team player or a stand-in nickname",
});

const MapSchema = z.object({
  map: z.enum(GAME_MAPS),
  ourScore: count(30),
  theirScore: count(30),
  pistol1: z.enum(["won", "lost"]),
  pistol2: z.enum(["won", "lost"]),
  rows: z.array(RowSchema).min(1).max(MAX_ROWS_PER_MAP),
}).refine((map) => {
  const ids = map.rows.flatMap((row) => (row.playerId === undefined ? [] : [row.playerId]));
  return new Set(ids).size === ids.length;
}, { message: "a player appears once per map", path: ["rows"] });

const GameSchema = z.object({
  id: uuid.optional(),
  kind: z.enum(GAME_KINDS),
  playedOn: isoDate,
  opponent: z.string().trim().max(60).nullish().transform((v) => (v ? v : null)),
  maps: z.array(MapSchema).min(1).max(MAX_GAME_MAPS),
});

type ParsedGame = z.output<typeof GameSchema>;

/** The checked game, or `422 validation_failed` naming the offending fields. */
export function parseGameInput(raw: unknown): ParsedGame {
  const parsed = GameSchema.safeParse(raw);
  if (parsed.success) return parsed.data;
  const message = parsed.error.issues.map((i) => `${i.path.join(".") || "game"}: ${i.message}`).join("; ");
  throw new ApiError("validation_failed", message, 422);
}

// ---------------------------------------------------------------------------------------------------- output

export interface GameRow extends RowStats {
  id: string;
  /** The nickname the line was entered with. */
  nickname: string;
  multikills: number | null;
  clutches: number | null;
  flashTime: number | null;
}

export interface GameMap extends MapStats {
  id: string;
  position: number;
  map: GameMapName;
  rows: GameRow[];
}

export interface Game {
  id: string;
  kind: GameKind;
  playedOn: string;
  opponent: string | null;
  createdBy: string | null;
  createdAt: Date;
  updatedAt: Date;
  maps: GameMap[];
}

/** A game with its totals: of the whole lineup, or of one player's maps and lines when listed with `playerId`. */
export interface GameSummary extends Game {
  totals: StatTotals;
}

export interface GameFilter {
  /** First and last day of the period, `YYYY-MM-DD`, both inclusive. */
  from: string;
  to: string;
  kind?: GameKind;
  /** Only games this player has a line in. */
  playerId?: string;
}

// ---------------------------------------------------------------------------------------------------- reading

const UUID = z.string().uuid();

interface GameRecord {
  id: string; kind: GameKind; played_on: string; opponent: string | null; created_by: string | null;
  created_at: Date; updated_at: Date;
}

const GAME_COLUMNS = (db: Queryable) => db`
  g.id, g.kind, to_char(g.played_on, 'YYYY-MM-DD') as played_on, g.opponent, g.created_by, g.created_at, g.updated_at`;

async function withMaps(db: Queryable, records: readonly GameRecord[]): Promise<Game[]> {
  if (records.length === 0) return [];
  const ids = records.map((g) => g.id);
  const maps = await db<{
    id: string; game_id: string; position: number; map: GameMapName; our_score: number; their_score: number;
    pistol1: PistolResult; pistol2: PistolResult;
  }[]>`
    select id, game_id, position, map, our_score, their_score, pistol1, pistol2
    from team_game_maps where game_id in ${db(ids)} order by game_id, position`;
  const rows = maps.length === 0 ? [] : await db<{
    id: string; map_id: string; player_id: string | null; nickname: string; kills: number; deaths: number; assists: number;
    adr: number; fk: number | null; fd: number | null; multikills: number | null; clutches: number | null; flash_time: number | null;
  }[]>`
    select id, map_id, player_id, nickname, kills, deaths, assists, adr::float8 as adr, fk, fd,
      multikills::float8 as multikills, clutches, flash_time::float8 as flash_time
    from team_game_rows where map_id in ${db(maps.map((m) => m.id))} order by map_id, position`;
  const rowsByMap = Map.groupBy(rows, (r) => r.map_id);
  const mapsByGame = Map.groupBy(maps, (m) => m.game_id);
  return records.map((g) => ({
    id: g.id, kind: g.kind, playedOn: g.played_on, opponent: g.opponent, createdBy: g.created_by,
    createdAt: g.created_at, updatedAt: g.updated_at,
    maps: (mapsByGame.get(g.id) ?? []).map((m) => ({
      id: m.id, position: m.position, map: m.map, ourScore: m.our_score, theirScore: m.their_score,
      pistol1: m.pistol1, pistol2: m.pistol2,
      rows: (rowsByMap.get(m.id) ?? []).map((r) => ({
        id: r.id, playerId: r.player_id, nickname: r.nickname, kills: r.kills, deaths: r.deaths, assists: r.assists,
        adr: r.adr, fk: r.fk, fd: r.fd, multikills: r.multikills, clutches: r.clutches, flashTime: r.flash_time,
      })),
    })),
  }));
}

/** One game of the caller's team; null for another team's game, an unknown id or a malformed one. */
export async function getGame(deps: Deps, ctx: StaffContext, id: string): Promise<Game | null> {
  if (!UUID.safeParse(id).success) return null;
  const records = await deps.sql<GameRecord[]>`
    select ${GAME_COLUMNS(deps.sql)} from team_games g where g.id = ${id.toLowerCase()} and g.team_id = ${ctx.teamId}`;
  return (await withMaps(deps.sql, records))[0] ?? null;
}

function requirePeriodDay(value: string, name: string): string {
  const parsed = isoDate.safeParse(value);
  if (!parsed.success) throw new ApiError("validation_failed", `${name}: expected a date as YYYY-MM-DD`, 422);
  return parsed.data;
}

/** The team's games in the period, newest first, each with its totals. */
export async function listGames(deps: Deps, ctx: StaffContext, filter: GameFilter): Promise<GameSummary[]> {
  const from = requirePeriodDay(filter.from, "from");
  const to = requirePeriodDay(filter.to, "to");
  const kind = filter.kind ?? null;
  if (kind !== null && !GAME_KINDS.includes(kind)) throw new ApiError("validation_failed", "kind: unknown game kind", 422);
  const playerId = filter.playerId?.toLowerCase() ?? null;
  if (playerId !== null && !UUID.safeParse(playerId).success) return [];
  const records = await deps.sql<GameRecord[]>`
    select ${GAME_COLUMNS(deps.sql)}
    from team_games g
    where g.team_id = ${ctx.teamId}
      and g.played_on between ${from}::date and ${to}::date
      and (${kind}::text is null or g.kind = ${kind})
      and (${playerId}::uuid is null or exists (
        select 1 from team_game_maps m join team_game_rows r on r.map_id = m.id
        where m.game_id = g.id and r.player_id = ${playerId}::uuid))
    order by g.played_on desc, g.created_at desc, g.id`;
  const games = await withMaps(deps.sql, records);
  return games.map((game) => ({ ...game, totals: gameTotals(game.maps, playerId ?? undefined) }));
}

// ---------------------------------------------------------------------------------------------------- writing

const forbidden = () => new ApiError("forbidden", "Staff of the team only.", 403);
const notFound = () => new ApiError("not_found", "No such game.", 404);

/** Defence in depth: the context must still describe a staff member of that very team. */
async function requireTeamStaff(deps: Deps, ctx: StaffContext): Promise<void> {
  const current = await requireStaff(deps, ctx.userId);
  if (current.teamId !== ctx.teamId) throw forbidden();
}

const lockGame = async (tx: TransactionSql, id: string, teamId: string) =>
  (await tx<{ id: string }[]>`select id from team_games where id = ${id} and team_id = ${teamId} for update`)[0] ?? null;

/** Nicknames of the players already on the game: their lines stay editable after the player left the team. */
async function currentPlayerNicknames(tx: TransactionSql, gameId: string): Promise<Map<string, string>> {
  const rows = await tx<{ player_id: string; nickname: string }[]>`
    select r.player_id, r.nickname
    from team_game_rows r join team_game_maps m on m.id = r.map_id
    where m.game_id = ${gameId} and r.player_id is not null`;
  return new Map(rows.map((r) => [r.player_id, r.nickname]));
}

function nicknameOf(row: ParsedGame["maps"][number]["rows"][number], known: ReadonlyMap<string, string>): string {
  if (row.playerId === undefined) return row.nickname as string;
  const nickname = known.get(row.playerId);
  if (nickname === undefined) throw new ApiError("validation_failed", "A player of the game is not on your team.", 422);
  return nickname;
}

async function insertMaps(tx: TransactionSql, gameId: string, game: ParsedGame, known: ReadonlyMap<string, string>) {
  const maps = await tx<{ id: string; position: number }[]>`
    insert into team_game_maps ${tx(
      game.maps.map((m, position) => ({
        game_id: gameId, position, map: m.map, our_score: m.ourScore, their_score: m.theirScore,
        pistol1: m.pistol1, pistol2: m.pistol2,
      })),
    )}
    returning id, position`;
  const mapIds = new Map(maps.map((m) => [m.position, m.id]));
  const rows = game.maps.flatMap((m, mapPosition) => m.rows.map((r, position) => ({
    map_id: mapIds.get(mapPosition) as string, position, player_id: r.playerId ?? null, nickname: nicknameOf(r, known),
    kills: r.kills, deaths: r.deaths, assists: r.assists, adr: r.adr, fk: r.fk, fd: r.fd,
    multikills: r.multikills, clutches: r.clutches, flash_time: r.flashTime,
  })));
  await tx`insert into team_game_rows ${tx(rows)}`;
}

/**
 * Creates a game, or replaces the one with `input.id` when it belongs to the team (its origin, author and creation
 * time are kept; maps and lines are rewritten). One transaction: a failure leaves the old game untouched. An id that
 * belongs to another team is `404`. Returns the game's id.
 */
export async function saveGame(deps: Deps, ctx: StaffContext, input: GameInput): Promise<string> {
  await requireTeamStaff(deps, ctx);
  const game = parseGameInput(input);
  const roster = new Map((await rosterMembers(deps, ctx)).map((m) => [m.id, m.nickname]));
  const now = deps.now();
  const id = game.id ?? crypto.randomUUID();
  await deps.sql.begin(async (tx) => {
    let existing = await lockGame(tx, id, ctx.teamId);
    if (!existing) {
      const inserted = await tx<{ id: string }[]>`
        insert into team_games (id, team_id, kind, played_on, opponent, created_by, created_at, updated_at)
        values (${id}, ${ctx.teamId}, ${game.kind}, ${game.playedOn}, ${game.opponent}, ${ctx.userId}, ${now}, ${now})
        on conflict (id) do nothing returning id`;
      if (inserted.length === 0) {
        // Somebody else took the id first: a concurrent save of the same game of this team becomes an edit.
        existing = await lockGame(tx, id, ctx.teamId);
        if (!existing) throw notFound();
      }
    }
    const known = new Map(roster);
    if (existing) {
      for (const [playerId, nickname] of await currentPlayerNicknames(tx, id)) {
        if (!known.has(playerId)) known.set(playerId, nickname);
      }
      await tx`
        update team_games set kind = ${game.kind}, played_on = ${game.playedOn}, opponent = ${game.opponent}, updated_at = ${now}
        where id = ${id}`;
      await tx`delete from team_game_maps where game_id = ${id}`;
    }
    await insertMaps(tx, id, game, known);
  });
  return id;
}

/** Deletes a game of the caller's team with its maps and lines; false when there is no such game. */
export async function deleteGame(deps: Deps, ctx: StaffContext, id: string): Promise<boolean> {
  await requireTeamStaff(deps, ctx);
  if (!UUID.safeParse(id).success) return false;
  const rows = await deps.sql<{ id: string }[]>`
    delete from team_games where id = ${id.toLowerCase()} and team_id = ${ctx.teamId} returning id`;
  return rows.length > 0;
}
