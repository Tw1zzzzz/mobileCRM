import {
  CELL_KEYS, TEAM, GAME_KIND_OPTIONS, GAME_MAP_OPTIONS, MAX_MAPS, MAX_NICKNAME, MAX_OPPONENT, MAX_ROWS,
  type Cells, type Draft, type DraftMap, type DraftRow,
} from "./draft";

/**
 * «Черновик сохраняется автоматически»: the unsaved new game lives in this browser's localStorage, one per team,
 * and goes when the game is saved or cleared. A stored draft is only ever read back through `readDraft`, which
 * rebuilds it field by field, so a damaged or foreign value cannot put anything unexpected into the form.
 */

export const draftStorageKey = (teamId: string): string => `pulse.staff.game-draft.${teamId}`;

const VERSION = 1;
const MAX_CELL = 12;

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === "object" && value !== null && !Array.isArray(value);
const str = (value: unknown, max: number): string => (typeof value === "string" ? value.slice(0, max) : "");

function readCells(value: unknown): Cells {
  const source = isRecord(value) ? value : {};
  return Object.fromEntries(CELL_KEYS.map((key) => [key, str(source[key], MAX_CELL)])) as Cells;
}

function readRow(value: unknown): DraftRow | null {
  if (!isRecord(value)) return null;
  const playerId = typeof value.playerId === "string" ? value.playerId : null;
  const key = playerId ?? str(value.key, 60);
  if (key === "") return null;
  return { key, playerId, nickname: str(value.nickname, MAX_NICKNAME), absent: playerId !== null && value.absent === true, cells: readCells(value.cells) };
}

function readMap(value: unknown): DraftMap | null {
  if (!isRecord(value) || !Array.isArray(value.rows)) return null;
  const map = GAME_MAP_OPTIONS.find((option) => option.value === value.map)?.value ?? "";
  const pistol = (raw: unknown) => (raw === "won" || raw === "lost" ? raw : "");
  const rows = value.rows.slice(0, MAX_ROWS * 2).map(readRow).filter((row): row is DraftRow => row !== null);
  return { map, our: str(value.our, 2), their: str(value.their, 2), pistol1: pistol(value.pistol1), pistol2: pistol(value.pistol2), rows };
}

/** The draft stored in `raw`, or null when there is none or it is not a draft of this version. */
export function readDraft(raw: string | null): Draft | null {
  if (raw === null) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(value) || value.v !== VERSION || !isRecord(value.draft) || value.draft.id !== null) return null;
  const draft = value.draft;
  const maps = Array.isArray(draft.maps) ? draft.maps.slice(0, MAX_MAPS).map(readMap).filter((map): map is DraftMap => map !== null) : [];
  const kind = GAME_KIND_OPTIONS.find((option) => option.value === draft.kind)?.value;
  if (maps.length === 0 || kind === undefined) return null;
  const active = typeof draft.active === "number" && Number.isInteger(draft.active) ? Math.min(Math.max(draft.active, 0), maps.length - 1) : 0;
  return {
    id: null, kind, playedOn: str(draft.playedOn, 10), opponent: str(draft.opponent, MAX_OPPONENT),
    who: str(draft.who, 60) || TEAM, maps, active,
  };
}

export const writeDraft = (draft: Draft): string => JSON.stringify({ v: VERSION, draft });

// Browsers may refuse storage (private windows, blocked site data): the form then simply works without a draft.

export function loadStoredDraft(teamId: string): Draft | null {
  try {
    return readDraft(window.localStorage.getItem(draftStorageKey(teamId)));
  } catch {
    return null;
  }
}

export function storeDraft(teamId: string, draft: Draft): void {
  try {
    window.localStorage.setItem(draftStorageKey(teamId), writeDraft(draft));
  } catch {
    /* no storage: nothing to keep */
  }
}

export function clearStoredDraft(teamId: string): void {
  try {
    window.localStorage.removeItem(draftStorageKey(teamId));
  } catch {
    /* no storage: nothing to clear */
  }
}

// The id a game is being sent under, kept with the draft: after a lost answer (or a reload) the retry reuses it, so
// a game the server did commit is replaced instead of doubled. Gone as soon as the draft is saved, cleared or blank.

const attemptKey = (teamId: string): string => `${draftStorageKey(teamId)}.attempt`;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function loadAttemptId(teamId: string): string | null {
  try {
    const id = window.localStorage.getItem(attemptKey(teamId));
    return id !== null && UUID.test(id) ? id : null;
  } catch {
    return null;
  }
}

export function storeAttemptId(teamId: string, id: string | null): void {
  try {
    if (id === null) window.localStorage.removeItem(attemptKey(teamId));
    else window.localStorage.setItem(attemptKey(teamId), id);
  } catch {
    /* no storage: a retry after a reload sends a new id */
  }
}
