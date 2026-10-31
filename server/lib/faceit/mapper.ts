/**
 * Faceit per-match stats -> the panel's match. A port of the app's `FaceitMapper.match` (core/.../model/Faceit.kt)
 * with the same loose number reading; checked against the same raw items in `test/fixtures/faceit-matches.json`.
 */

/** One CS2 match of the player. Optional fields are left out when Faceit did not send them. */
export interface FaceitMatch {
  id: string;
  /** Faceit's map name as sent, e.g. "de_inferno"; "" when absent. */
  map: string;
  won: boolean;
  /** Rounds the player's team won / lost; 0 when Faceit sent no readable score. */
  scoreOwn: number;
  scoreOther: number;
  kills: number;
  deaths: number;
  assists: number;
  adr: number;
  rounds: number;
  /** ISO instant with milliseconds. */
  finishedAt: string;
  firstKills?: number;
  entryCount?: number;
  entryWins?: number;
  /** 1v1 + 1v2 clutches won. */
  clutchWins?: number;
  multiKills?: { k2: number; k3: number; k4: number; k5: number };
  utilityDamage?: number;
  enemiesFlashed?: number;
}

type Stats = Record<string, unknown>;

const NUMBER_TEXT = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
const WHOLE_NUMBER_TEXT = /^[+-]?\d+$/;
const UUID_TEXT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** Above this a timestamp is milliseconds, below it seconds (the app's rule). */
const MILLISECONDS_THRESHOLD = 10_000_000_000;

function isStats(value: unknown): value is Stats {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** `faceitDouble`: a number, or text with "," or "." as the decimal mark. */
function looseDouble(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string") return null;
  const text = value.replace(",", ".").trim();
  if (!NUMBER_TEXT.test(text)) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/** `faceitString`: text as is, a whole number as its digits. */
function looseString(value: unknown): string | null {
  if (typeof value === "string") return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
}

function firstDouble(stats: Stats, keys: readonly string[]): number | null {
  for (const key of keys) {
    const value = looseDouble(stats[key]);
    if (value !== null) return value;
  }
  return null;
}

/** Swift `.rounded()`: halves go away from zero. */
function roundAwayFromZero(x: number): number {
  return x < 0 ? -Math.floor(-x + 0.5) : Math.floor(x + 0.5);
}

function int(stats: Stats, ...keys: string[]): number | null {
  const value = firstDouble(stats, keys);
  return value === null ? null : roundAwayFromZero(value);
}

function text(stats: Stats, key: string): string | null {
  return looseString(stats[key]);
}

/** "13 / 9" -> [13, 9]; anything else -> null. */
function parseScore(raw: string | null): [number, number] | null {
  if (raw === null) return null;
  const numbers = raw
    .split("/")
    .map((part) => part.trim())
    .filter((part) => WHOLE_NUMBER_TEXT.test(part))
    .map(Number);
  return numbers.length === 2 ? [numbers[0], numbers[1]] : null;
}

function matchId(stats: Stats, finishedAt: string, kills: number, deaths: number): string {
  const raw = text(stats, "Match Id");
  if (raw === null) return `m-${Date.parse(finishedAt)}-${kills}-${deaths}`;
  const bare = raw.startsWith("1-") ? raw.slice(2) : raw;
  const lower = bare.toLowerCase();
  return UUID_TEXT.test(lower) ? lower : bare;
}

function finishedAtOf(stats: Stats, now: Date): string {
  const stamp = firstDouble(stats, ["Match Finished At", "Updated At", "Created At"]);
  if (stamp === null) return now.toISOString();
  const seconds = stamp > MILLISECONDS_THRESHOLD ? stamp / 1000 : stamp;
  const date = new Date(Math.trunc(seconds * 1000));
  return Number.isNaN(date.getTime()) ? now.toISOString() : date.toISOString();
}

/**
 * One item of Faceit's `items[]` (`{ stats: {...} }`) -> a match, or null when it is not an object with `stats` or
 * lacks `Kills` or `Deaths`. A match without any timestamp gets `now`, as in the app.
 */
export function mapMatch(item: unknown, now: Date = new Date()): FaceitMatch | null {
  if (!isStats(item) || !isStats(item.stats)) return null;
  const stats = item.stats;
  const kills = int(stats, "Kills");
  const deaths = int(stats, "Deaths");
  if (kills === null || deaths === null) return null;

  const finishedAt = finishedAtOf(stats, now);
  const score = parseScore(text(stats, "Score"));
  const won = text(stats, "Result") === "1";
  const [high, low] = score ? [Math.max(...score), Math.min(...score)] : [0, 0];
  const scoreOwn = won ? high : low;
  const scoreOther = won ? low : high;

  const match: FaceitMatch = {
    id: matchId(stats, finishedAt, kills, deaths),
    map: text(stats, "Map") ?? "",
    won,
    scoreOwn,
    scoreOther,
    kills,
    deaths,
    assists: int(stats, "Assists") ?? 0,
    adr: firstDouble(stats, ["ADR"]) ?? 0,
    rounds: Math.max(int(stats, "Rounds") ?? scoreOwn + scoreOther, 1),
    finishedAt,
  };

  const firstKills = int(stats, "First Kills");
  if (firstKills !== null) match.firstKills = firstKills;
  const entryCount = int(stats, "Entry Count");
  if (entryCount !== null) match.entryCount = entryCount;
  const entryWins = int(stats, "Entry Wins");
  if (entryWins !== null) match.entryWins = entryWins;
  const clutch1 = int(stats, "1v1Wins");
  const clutch2 = int(stats, "1v2Wins");
  if (clutch1 !== null || clutch2 !== null) match.clutchWins = (clutch1 ?? 0) + (clutch2 ?? 0);
  const multi = ["Double Kills", "Triple Kills", "Quadro Kills", "Penta Kills"].map((key) => int(stats, key));
  if (multi.some((v) => v !== null)) {
    match.multiKills = { k2: multi[0] ?? 0, k3: multi[1] ?? 0, k4: multi[2] ?? 0, k5: multi[3] ?? 0 };
  }
  const utilityDamage = int(stats, "Utility Damage");
  if (utilityDamage !== null) match.utilityDamage = utilityDamage;
  const enemiesFlashed = int(stats, "Enemies Flashed");
  if (enemiesFlashed !== null) match.enemiesFlashed = enemiesFlashed;
  return match;
}
