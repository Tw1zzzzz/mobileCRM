import type { Game, GameInput, GameKind, GameMapInput, GameMapName, GameRowInput } from "@/lib/staff/games";
import type { PistolResult } from "@/lib/staff/games-math";

/**
 * The game being typed in «Внести игру»: every field is the text of its input, so half-typed numbers survive.
 * Plain data and pure functions (each returns a new draft, nothing is changed in place); the form component only
 * holds the current draft in state. `check.ts` says what is still missing, `figures.ts` the numbers derived from it.
 */

export const GAME_KIND_OPTIONS: readonly { value: GameKind; label: string }[] = [
  { value: "pracc", label: "Пракк" },
  { value: "tournament", label: "Турнир" },
];

/** The maps of the board's picker (the base ones, then the two extra ones the games accept). */
export const GAME_MAP_OPTIONS: readonly { value: GameMapName; label: string }[] = [
  { value: "mirage", label: "Mirage" },
  { value: "inferno", label: "Inferno" },
  { value: "nuke", label: "Nuke" },
  { value: "ancient", label: "Ancient" },
  { value: "anubis", label: "Anubis" },
  { value: "dust2", label: "Dust2" },
  { value: "train", label: "Train" },
  { value: "overpass", label: "Overpass" },
  { value: "vertigo", label: "Vertigo" },
];

export const MAX_MAPS = 5;
export const MAX_ROWS = 10;
export const MAX_OPPONENT = 60;
export const MAX_NICKNAME = 40;

// ---------------------------------------------------------------------------------------------------- cells

/** The board's 11 input columns, in the order a spreadsheet pasted into the table is read. */
export const CELL_KEYS = ["kills", "deaths", "assists", "adr", "kast", "fk", "fd", "udr", "multikills", "clutches", "flashTime"] as const;
export type CellKey = (typeof CELL_KEYS)[number];
export type Cells = Record<CellKey, string>;

export interface CellSpec {
  key: CellKey;
  /** Column heading of the board. */
  label: string;
  /** Tooltip of the heading. */
  title: string;
  required: boolean;
  max: number;
  /** Decimal places kept (the game stores whole numbers or one decimal); more are rounded away. */
  decimals: 0 | 1;
}

export const CELLS: readonly CellSpec[] = [
  { key: "kills", label: "K", title: "Убийства", required: true, max: 99, decimals: 0 },
  { key: "deaths", label: "D", title: "Смерти", required: true, max: 99, decimals: 0 },
  { key: "assists", label: "A", title: "Ассисты", required: true, max: 99, decimals: 0 },
  { key: "adr", label: "ADR", title: "Средний урон за раунд", required: true, max: 300, decimals: 1 },
  { key: "kast", label: "KAST %", title: "KAST, %", required: false, max: 100, decimals: 1 },
  { key: "fk", label: "FK", title: "First Kills", required: false, max: 30, decimals: 0 },
  { key: "fd", label: "FD", title: "First Deaths", required: false, max: 30, decimals: 0 },
  { key: "udr", label: "UDR", title: "Utility damage per round", required: false, max: 500, decimals: 1 },
  { key: "multikills", label: "Мульти", title: "Ср. мультикиллы", required: false, max: 10, decimals: 1 },
  { key: "clutches", label: "Клатчи", title: "Выигранные клатчи", required: false, max: 20, decimals: 0 },
  { key: "flashTime", label: "Ослепл.", title: "Ср. время ослепления, с", required: false, max: 60, decimals: 1 },
];

const SPEC_OF: Record<CellKey, CellSpec> = Object.fromEntries(CELLS.map((spec) => [spec.key, spec])) as Record<CellKey, CellSpec>;

export type CellValue = { kind: "empty" } | { kind: "ok"; value: number } | { kind: "bad" };

const NUMBER = /^\d+(?:[.,]\d+)?$/;

/** A number rounded to the precision of its cell (76.25 → 76.3 for ADR, 24.5 → 25 for K). */
function roundTo(decimals: 0 | 1, value: number): number {
  return decimals === 0 ? Math.round(value) : Math.round(Number((value * 10).toFixed(6))) / 10;
}

/**
 * What the text of a cell means: nothing yet, a number within the game's limits, or something the game refuses.
 * Comma or dot is accepted as the decimal separator, one trailing % is ignored; digits beyond the cell's precision are rounded, not refused.
 */
export function readCell(key: CellKey, text: string): CellValue {
  const trimmed = text.trim().replace(/%$/, "").trim();
  if (text.trim() === "") return { kind: "empty" };
  const spec = SPEC_OF[key];
  if (!NUMBER.test(trimmed)) return { kind: "bad" };
  const value = roundTo(spec.decimals, Number(trimmed.replace(",", ".")));
  return value <= spec.max ? { kind: "ok", value } : { kind: "bad" };
}

/** The text a number is shown as: a decimal comma, like the rest of the panel. */
export const showNumber = (value: number): string => String(value).replace(".", ",");

/** A typed or pasted value as it is kept: rounded to the cell's precision with a comma; anything else as typed. */
export function normalizeCell(key: CellKey, text: string): string {
  const cell = readCell(key, text);
  return cell.kind === "ok" ? showNumber(cell.value) : text;
}

/** A whole number typed as a score: digits only, 0–30. */
export function readScore(text: string): number | null {
  if (!/^\d{1,2}$/.test(text.trim())) return null;
  const value = Number(text);
  return value <= 30 ? value : null;
}

// ---------------------------------------------------------------------------------------------------- draft

export const TEAM = "team";

export interface DraftRow {
  /** Stable React key: the player's id, or a random one for a stand-in. */
  key: string;
  /** A player of the team; null for a stand-in typed by nickname. */
  playerId: string | null;
  nickname: string;
  /** «Не играл»: a roster player left out of this map (the line is kept so that it can come back). */
  absent: boolean;
  cells: Cells;
}

export interface DraftMap {
  map: GameMapName | "";
  our: string;
  their: string;
  pistol1: PistolResult | "";
  pistol2: PistolResult | "";
  rows: DraftRow[];
}

export interface Draft {
  /** The game being edited; null for a new one. */
  id: string | null;
  kind: GameKind;
  /** `YYYY-MM-DD`. */
  playedOn: string;
  opponent: string;
  /** «Кто играл»: `TEAM` or the id of one player. */
  who: string;
  maps: DraftMap[];
  /** Index of the map shown in steps 2 and 3. */
  active: number;
}

export interface RosterPlayer {
  id: string;
  nickname: string;
}

const emptyCells = (): Cells => Object.fromEntries(CELL_KEYS.map((key) => [key, ""])) as Cells;

export const playerRow = (player: RosterPlayer): DraftRow => ({
  key: player.id, playerId: player.id, nickname: player.nickname, absent: false, cells: emptyCells(),
});

export const standInRow = (nickname = ""): DraftRow => ({ key: `stand-in-${crypto.randomUUID()}`, playerId: null, nickname, absent: false, cells: emptyCells() });

const blankMap = (rows: DraftRow[]): DraftMap => ({ map: "", our: "", their: "", pistol1: "", pistol2: "", rows });

export function newDraft(roster: readonly RosterPlayer[], today: string, keep?: Pick<Draft, "kind" | "playedOn" | "who">): Draft {
  return {
    id: null, kind: keep?.kind ?? "pracc", playedOn: keep?.playedOn ?? today, opponent: "", who: keep?.who ?? TEAM,
    maps: [blankMap(roster.map(playerRow))], active: 0,
  };
}

/** The rows of a map that belong to the game under «Кто играл» (in «Команда», without those marked «Не играл»). */
export function visibleRows(draft: Pick<Draft, "who">, map: DraftMap): DraftRow[] {
  return draft.who === TEAM ? map.rows.filter((row) => !row.absent) : map.rows.filter((row) => row.playerId === draft.who);
}

/** The roster players marked «Не играл» on a map, in «Команда» mode. */
export function absentRows(draft: Pick<Draft, "who">, map: DraftMap): DraftRow[] {
  return draft.who === TEAM ? map.rows.filter((row) => row.absent) : [];
}

/** Nothing typed yet apart from the type, the date and who played (a draft like that is not worth keeping). */
export function isBlank(draft: Draft): boolean {
  return (
    draft.opponent.trim() === "" &&
    draft.maps.length === 1 &&
    draft.maps.every(
      (map) =>
        map.map === "" && map.our === "" && map.their === "" && map.pistol1 === "" && map.pistol2 === "" &&
        map.rows.every((row) => row.playerId !== null && !row.absent && Object.values(row.cells).every((cell) => cell === "")),
    )
  );
}

export const activeMap = (draft: Draft): DraftMap => draft.maps[Math.min(draft.active, draft.maps.length - 1)];

// ---------------------------------------------------------------------------------------------------- changes

function replaceAt<T>(items: readonly T[], index: number, change: (item: T) => T): T[] {
  return items.map((item, i) => (i === index ? change(item) : item));
}

export const withField = <K extends "kind" | "playedOn" | "opponent">(draft: Draft, key: K, value: Draft[K]): Draft => ({ ...draft, [key]: value });

export function withWho(draft: Draft, who: string): Draft {
  return { ...draft, who };
}

export function withMapChange(draft: Draft, index: number, change: Partial<Omit<DraftMap, "rows">>): Draft {
  return { ...draft, maps: replaceAt(draft.maps, index, (map) => ({ ...map, ...change })) };
}

function withRowChange(draft: Draft, mapIndex: number, rowKey: string, change: (row: DraftRow) => DraftRow): Draft {
  return {
    ...draft,
    maps: replaceAt(draft.maps, mapIndex, (map) => ({ ...map, rows: map.rows.map((row) => (row.key === rowKey ? change(row) : row)) })),
  };
}

export const withCell = (draft: Draft, mapIndex: number, rowKey: string, key: CellKey, value: string): Draft =>
  withRowChange(draft, mapIndex, rowKey, (row) => ({ ...row, cells: { ...row.cells, [key]: value } }));

/** «Не играл» / «Вернуть»: leaves a roster player out of one map, or puts the line back. */
export const withAbsent = (draft: Draft, mapIndex: number, rowKey: string, absent: boolean): Draft =>
  withRowChange(draft, mapIndex, rowKey, (row) => ({ ...row, absent }));

export const withNickname = (draft: Draft, mapIndex: number, rowKey: string, nickname: string): Draft =>
  withRowChange(draft, mapIndex, rowKey, (row) => ({ ...row, nickname }));

/** Writes pasted values: `[row, column]` positions count from the first visible row and the first column. */
export function withPastedCells(
  draft: Draft, mapIndex: number, cells: readonly { row: number; col: number; value: string }[],
): Draft {
  return {
    ...draft,
    maps: replaceAt(draft.maps, mapIndex, (map) => {
      const shown = visibleRows(draft, map).map((row) => row.key);
      return {
        ...map,
        rows: map.rows.map((row) => {
          const mine = cells.filter((cell) => shown[cell.row] === row.key);
          if (mine.length === 0) return row;
          const changed = Object.fromEntries(mine.map((cell) => [CELLS[cell.col].key, normalizeCell(CELLS[cell.col].key, cell.value)]));
          return { ...row, cells: { ...row.cells, ...changed } };
        }),
      };
    }),
  };
}

/** A stand-in line on one map. */
export function withStandIn(draft: Draft, mapIndex: number): Draft {
  return { ...draft, maps: replaceAt(draft.maps, mapIndex, (map) => ({ ...map, rows: [...map.rows, standInRow()] })) };
}

export function withoutRow(draft: Draft, mapIndex: number, rowKey: string): Draft {
  return { ...draft, maps: replaceAt(draft.maps, mapIndex, (map) => ({ ...map, rows: map.rows.filter((row) => row.key !== rowKey) })) };
}

/** A new empty map after the last one, with the same players and stand-ins as it. */
export function withNewMap(draft: Draft): Draft {
  if (draft.maps.length >= MAX_MAPS) return draft;
  const last = draft.maps[draft.maps.length - 1];
  const rows = last.rows.map((row) => ({ ...row, cells: emptyCells() }));
  return { ...draft, maps: [...draft.maps, blankMap(rows)], active: draft.maps.length };
}

export function withoutMap(draft: Draft, index: number): Draft {
  if (draft.maps.length <= 1) return draft;
  return { ...draft, maps: draft.maps.filter((_, i) => i !== index), active: Math.max(0, index - 1) };
}

export const withActive = (draft: Draft, active: number): Draft => ({ ...draft, active });

// ---------------------------------------------------------------------------------------------------- roster

export interface RosterRules {
  /** Players who may stay on the game although they are not in the roster (they were on it when it was saved). */
  keep: ReadonlySet<string>;
  /** Give every roster player a line on every map (a new game), or leave the lines as they are (an old game). */
  fill: boolean;
}

/**
 * Brings a draft in line with the current roster: roster players first in roster order (with the current nickname),
 * then the players allowed to stay, then the stand-ins. A player who left the team and is not allowed to stay loses
 * their line. A single player who is gone turns «Кто играл» back to the team.
 */
export function withRoster(draft: Draft, roster: readonly RosterPlayer[], rules: RosterRules): Draft {
  const ids = new Set(roster.map((player) => player.id));
  const maps = draft.maps.map((map) => {
    const byPlayer = new Map(map.rows.flatMap((row) => (row.playerId === null ? [] : [[row.playerId, row] as const])));
    const current = roster.flatMap((player) => {
      const row = byPlayer.get(player.id);
      if (row) return [{ ...row, nickname: player.nickname }];
      return rules.fill ? [playerRow(player)] : [];
    });
    const staying = map.rows.filter((row) => row.playerId !== null && !ids.has(row.playerId) && rules.keep.has(row.playerId));
    return { ...map, rows: [...current, ...staying, ...map.rows.filter((row) => row.playerId === null)] };
  });
  const who = draft.who === TEAM || ids.has(draft.who) ? draft.who : TEAM;
  return { ...draft, maps, who };
}

// ---------------------------------------------------------------------------------------------------- from and to a game

/** Who played, read from a saved game: one roster player on every map, else the team. */
function whoPlayed(game: Game, roster: readonly RosterPlayer[]): string {
  const ids = new Set(game.maps.flatMap((m) => (m.rows.length === 1 ? [m.rows[0].playerId] : [null])));
  const [only] = [...ids];
  return ids.size === 1 && only !== null && roster.some((player) => player.id === only) ? only : TEAM;
}

const text = (value: number | null): string => (value === null ? "" : showNumber(value));

/** The draft of a saved game, for editing it. */
export function draftFromGame(game: Game, roster: readonly RosterPlayer[]): Draft {
  const draft: Draft = {
    id: game.id, kind: game.kind, playedOn: game.playedOn, opponent: game.opponent ?? "", who: whoPlayed(game, roster),
    active: 0,
    maps: game.maps.map((map) => ({
      map: map.map, our: String(map.ourScore), their: String(map.theirScore), pistol1: map.pistol1, pistol2: map.pistol2,
      rows: map.rows.map((row) => ({
        key: row.playerId ?? `stand-in-${row.id}`, playerId: row.playerId, nickname: row.nickname, absent: false,
        cells: {
          kills: text(row.kills), deaths: text(row.deaths), assists: text(row.assists), adr: text(row.adr),
          kast: text(row.kast), fk: text(row.fk), fd: text(row.fd), udr: text(row.udr),
          multikills: text(row.multikills), clutches: text(row.clutches), flashTime: text(row.flashTime),
        },
      })),
    })),
  };
  const keep = new Set(game.maps.flatMap((m) => m.rows.flatMap((row) => (row.playerId === null ? [] : [row.playerId]))));
  return withRoster(draft, roster, { keep, fill: false });
}

/** The game to save. Call it only for a draft `checkDraft` has no complaints about. */
export function toGameInput(draft: Draft, id?: string): GameInput {
  const opponent = draft.opponent.trim();
  const gameId = draft.id ?? id;
  return {
    ...(gameId === undefined ? {} : { id: gameId }),
    kind: draft.kind,
    playedOn: draft.playedOn,
    opponent: opponent === "" ? null : opponent,
    maps: draft.maps.map((map): GameMapInput => ({
      map: map.map as GameMapName,
      ourScore: readScore(map.our) ?? 0,
      theirScore: readScore(map.their) ?? 0,
      pistol1: map.pistol1 as PistolResult,
      pistol2: map.pistol2 as PistolResult,
      rows: visibleRows(draft, map).map((row): GameRowInput => {
        const number = (key: CellKey): number => {
          const cell = readCell(key, row.cells[key]);
          return cell.kind === "ok" ? cell.value : 0;
        };
        const optional = (key: CellKey): number | null => {
          const cell = readCell(key, row.cells[key]);
          return cell.kind === "ok" ? cell.value : null;
        };
        const who = row.playerId === null ? { nickname: row.nickname.trim() } : { playerId: row.playerId };
        return {
          ...who, kills: number("kills"), deaths: number("deaths"), assists: number("assists"), adr: number("adr"),
          kast: optional("kast"), fk: optional("fk"), fd: optional("fd"), udr: optional("udr"),
          multikills: optional("multikills"), clutches: optional("clutches"), flashTime: optional("flashTime"),
        };
      }),
    })),
  };
}
