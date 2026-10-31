/**
 * The card's Faceit tabs: «Игра» (tiles) and «Корреляции» (state × game matrix), as ready-to-render text.
 *
 * Pure functions over a `FaceitResult` (already loaded by `loadCard`). Numbers come from `gameSummary` /
 * `matchDaily` (`lib/staff/game.ts`) and `correlate` / `keyLinks` (`lib/staff/statistics.ts`); this file only
 * formats them the board's way (`Players.dc.html`). A value without data is «нет данных», never 0.
 */
import { MATCH_LIMIT } from "@/lib/faceit/client";
import type { FaceitResult } from "@/lib/faceit/cache";
import { monthDay } from "@/lib/staff/card-format";
import { gameSummary, matchDaily, type GameTiles } from "@/lib/staff/game";
import { dayOf, roundHalfUp, type Day } from "@/lib/staff/metrics";
import { NO_DATA, whole } from "@/lib/staff/overview-format";
import { clock } from "@/lib/staff/players";
import { keyLinks, correlate, type CorrelationMatrix, type LinkMetric } from "@/lib/staff/statistics";
import type { Period, PlayerSeries } from "@/lib/staff/series";

export const FACEIT_MESSAGES = {
  "no-key": "Faceit не подключён к серверу",
  "no-player": "У игрока не подключён Faceit",
  "not-found": "Игрок на Faceit не найден",
  "rate-limited": "Faceit временно недоступен — попробуйте позже",
  unavailable: "Faceit временно недоступен — попробуйте позже",
} as const;

export const NO_MATCHES = "В выбранном периоде матчей Faceit нет";
export const TOO_FEW_PAIRS = "мало данных";

/** What the Faceit tabs say about the data itself. */
export interface FaceitNoticeData {
  message: string;
  /** «26 окт, 12:30» when the matches shown are an older copy; null for a plain failure. */
  asOf: string | null;
}

export interface FaceitInfo {
  /** Null when Faceit answered with fresh data. */
  notice: FaceitNoticeData | null;
  /** «Матчи покрыты с 7 сен» when the period reaches further back than the 100 matches Faceit returned. */
  coverage: string | null;
}

export interface Tile {
  label: string;
  value: string;
}

export interface TileGroup {
  title: string;
  tiles: Tile[];
}

export interface GameView extends FaceitInfo {
  /** Empty when there are no matches to show (a Faceit problem). */
  groups: TileGroup[];
  /** Set when Faceit answered but the period has no match. */
  emptyPeriod: string | null;
}

export interface CorrCell {
  text: string;
  /** `rgba(...)`; null when there is no r. */
  background: string | null;
}

export interface CorrRow {
  name: string;
  cells: CorrCell[];
}

export interface CorrView extends FaceitInfo {
  head: string[];
  /** Empty when there are no matches to correlate (a Faceit problem). */
  rows: CorrRow[];
  /** The text after «Главное: »; null when no pair has an r. */
  note: string | null;
}

// ---------------------------------------------------------------- formats

/** Half-up fixed-point text (`toFixed` rounds the binary value, which can go either way on a half). */
export function fixed(value: number, digits: number): string {
  const factor = 10 ** digits;
  return (Math.floor(value * factor + 0.5) / factor).toFixed(digits);
}

/** The board's `sgn(v, 0)` with U+2212: «+3», «−3», «±0». */
function signedWhole(value: number): string {
  const rounded = roundHalfUp(value);
  return `${rounded > 0 ? "+" : rounded < 0 ? "−" : "±"}${Math.abs(rounded)}`;
}

export function signedR(r: number): string {
  return `${r >= 0 ? "+" : "−"}${fixed(Math.abs(r), 2)}`;
}

/** The value of a tile, or «нет данных». */
function or(value: number | null, format: (value: number) => string): string {
  return value === null ? NO_DATA : format(value);
}

const count = (value: number): string => String(roundHalfUp(value));
const dec1 = (value: number): string => fixed(value, 1);
const dec2 = (value: number): string => fixed(value, 2);
const percent = (value: number): string => `${whole(value)}%`;

// ---------------------------------------------------------------- notice and coverage

function dateTime(instant: Date, today: Day, tz: string): string {
  return `${monthDay(dayOf(instant, tz), today)}, ${clock(instant, tz)}`;
}

function oldestDay(result: Extract<FaceitResult, { status: "ok" }>, tz: string): Day | null {
  let oldest: number | null = null;
  for (const match of result.matches) {
    const at = Date.parse(match.finishedAt);
    if (!Number.isNaN(at) && (oldest === null || at < oldest)) oldest = at;
  }
  return oldest === null ? null : dayOf(new Date(oldest), tz);
}

/**
 * The message for a Faceit problem or a stale copy, and the coverage line. The line appears only when Faceit
 * returned the full 100 matches (the cap) and the period starts before the oldest of them.
 */
export function faceitInfo(result: FaceitResult, period: Period, today: Day, tz: string): FaceitInfo {
  if (result.status !== "ok") return { notice: { message: FACEIT_MESSAGES[result.status], asOf: null }, coverage: null };
  const notice = result.stale
    ? { message: FACEIT_MESSAGES.unavailable, asOf: dateTime(result.fetchedAt, today, tz) }
    : null;
  const oldest = result.matches.length >= MATCH_LIMIT ? oldestDay(result, tz) : null;
  const coverage = oldest !== null && period.from < oldest ? `Матчи покрыты с ${monthDay(oldest, today)}` : null;
  return { notice, coverage };
}

// ---------------------------------------------------------------- «Игра»

type TileSpec = readonly [label: string, value: (tiles: GameTiles) => string];

const KD_TILES: readonly TileSpec[] = [
  ["Убийства", (t) => or(t.kills, count)],
  ["Смерти", (t) => or(t.deaths, count)],
  ["Ассисты", (t) => or(t.assists, count)],
  ["K/D", (t) => or(t.kd, dec2)],
];

const ANALYTIC_TILES: readonly TileSpec[] = [
  ["ADR", (t) => or(t.adr, dec1)],
  ["KPR", (t) => or(t.kpr, dec2)],
  ["Death/round", (t) => or(t.deathsPerRound, dec2)],
  ["AVG KR", (t) => or(t.avgKR, dec2)],
  ["AVG KD", (t) => or(t.avgKD, dec2)],
  ["KAST", (t) => or(t.kast, percent)],
  ["First kills", (t) => or(t.firstKills, count)],
  ["First deaths", (t) => or(t.firstDeaths, count)],
  ["UDR", (t) => or(t.udr, dec1)],
  // Entry duels won minus lost, straight from `gameSummary` (ruling B4); never recomputed from first kills.
  ["Разница опен-дуэлей", (t) => or(t.openDuelDiff, signedWhole)],
  ["Ср. мультикиллы", (t) => or(t.avgMultikills, dec1)],
  ["Выигранные клатчи", (t) => or(t.clutchesWon, count)],
  ["Ср. время ослепления", (t) => or(t.avgFlashTime, (v) => `${dec1(v)} с`)],
];

const ROUND_TILES: readonly TileSpec[] = [
  ["Всего матчей", (t) => String(t.matches)],
  ["Победы", (t) => or(t.wins, count)],
  ["Поражения", (t) => or(t.losses, count)],
  ["Ничьи", (t) => or(t.draws, count)],
  ["Win-Rate", (t) => or(t.winRate, percent)],
  [
    "Раунды выиграно / всего",
    (t) => (t.roundsWon === null || t.rounds === null ? NO_DATA : `${count(t.roundsWon)} / ${count(t.rounds)}`),
  ],
  ["Round Win-Rate", (t) => or(t.roundWinRate, percent)],
  ["AVG Rounds Won", (t) => or(t.avgRoundsWon, dec1)],
  ["AVG Rounds Lost", (t) => or(t.avgRoundsLost, dec1)],
  [
    "Пистолетные выиграно / всего",
    (t) => (t.pistolWon === null || t.pistolRounds === null ? NO_DATA : `${count(t.pistolWon)} / ${count(t.pistolRounds)}`),
  ],
  ["Pistol Win-Rate", (t) => or(t.pistolWinRate, percent)],
];

const GROUPS: readonly (readonly [title: string, specs: readonly TileSpec[]])[] = [
  ["K/D", KD_TILES],
  ["Аналитические метрики", ANALYTIC_TILES],
  ["Матчи, раунды, пистолетки", ROUND_TILES],
];

/** The «Игра» tab for the period's matches. */
export function gameView(result: FaceitResult, period: Period, today: Day, tz: string): GameView {
  const info = faceitInfo(result, period, today, tz);
  if (result.status !== "ok") return { ...info, groups: [], emptyPeriod: null };
  const summary = gameSummary(result.matches, period.from, period.to, tz);
  return {
    ...info,
    groups: GROUPS.map(([title, specs]) => ({
      title,
      tiles: specs.map(([label, value]) => ({ label, value: value(summary) })),
    })),
    emptyPeriod: summary.matches === 0 ? NO_MATCHES : null,
  };
}

// ---------------------------------------------------------------- «Корреляции»

const STATE_ROWS = [
  ["Сон", "sleep"],
  ["Настроение", "mood"],
  ["Энергия", "energy"],
  ["Экранное время", "screen"],
  ["Когнитивный индекс", "cog"],
] as const;

/** The board's KAST column is dropped: Faceit has no KAST. */
const GAME_COLUMNS = [
  ["K/D", "kd"],
  ["ADR", "adr"],
  ["Win Rate", "winRate"],
] as const;

const LABELS: Record<string, string> = Object.fromEntries([...STATE_ROWS, ...GAME_COLUMNS].map(([label, id]) => [id, label]));

const LINK_METRICS: readonly LinkMetric[] = [
  ...STATE_ROWS.map(([, id]): LinkMetric => ({ id, group: "state" })),
  ...GAME_COLUMNS.map(([, id]): LinkMetric => ({ id, group: "game" })),
];

/** The board's cell colour: `a = 0.12 + |r|·0.7`, blue for a direct link, orange for an inverse one. */
export function cellColour(r: number): string {
  const alpha = fixed(0.12 + Math.abs(r) * 0.7, 2);
  return r >= 0 ? `rgba(0,140,255,${alpha})` : `rgba(255,122,61,${alpha})`;
}

function cellOf(r: number | null): CorrCell {
  return r === null ? { text: TOO_FEW_PAIRS, background: null } : { text: signedR(r), background: cellColour(r) };
}

function noteOf(matrix: CorrelationMatrix): string | null {
  const [best] = keyLinks(LINK_METRICS, matrix);
  if (best === undefined) return null;
  const effect = best.r >= 0 ? "тоже растёт" : "снижается";
  return `сильнее всего связаны «${LABELS[best.a]}» и «${LABELS[best.b]}» (r = ${signedR(best.r)}). Когда первое выше обычного, второе ${effect}.`;
}

/**
 * The «Корреляции» tab: r of each state series with each game series over the days both have (Pearson, at least
 * three paired days, else «мало данных»). The game side is the per-day mean over that day's matches.
 */
export function corrView(result: FaceitResult, series: PlayerSeries, period: Period, today: Day, tz: string): CorrView {
  const info = faceitInfo(result, period, today, tz);
  const head = GAME_COLUMNS.map(([label]) => label);
  if (result.status !== "ok") return { ...info, head, rows: [], note: null };

  const game = matchDaily(result.matches, period.from, period.to, tz);
  const matrix: CorrelationMatrix = {};
  const rows = STATE_ROWS.map(([name, row]): CorrRow => {
    const rs = GAME_COLUMNS.map(([, column]) => correlate(series[row], game[column]));
    matrix[row] = Object.fromEntries(GAME_COLUMNS.map(([, column], i) => [column, rs[i]]));
    return { name, cells: rs.map(cellOf) };
  });
  return { ...info, head, rows, note: noteOf(matrix) };
}
