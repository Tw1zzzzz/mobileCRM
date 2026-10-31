import type { Deps } from "@/lib/deps";
import { playerDocuments, teamPlayers, type Collection, type StaffContext } from "@/lib/staff/access";
import { chartLine, type TestsChart } from "@/lib/staff/card-charts";
import { foodData, type FoodData } from "@/lib/staff/card-food";
import { dayLabel, hoursMinutes, initials, monthDay, signed1 } from "@/lib/staff/card-format";
import { wheelData, type WheelData } from "@/lib/staff/card-wheel";
import {
  MIN_SLEEP_MS,
  STAFF_TIME_ZONE,
  dayOf,
  mean,
  parseInstant,
  inRange,
  records,
  shiftDay,
  type Day,
  type Documents,
} from "@/lib/staff/metrics";
import type { SearchParams } from "@/lib/staff/overview";
import { NO_DATA, whole } from "@/lib/staff/overview-format";
import { EMPTY_CELL, clock, lastActivityLabel } from "@/lib/staff/players";
import { periodDays, playerSeries, type DaySeries, type Period } from "@/lib/staff/series";

/**
 * The player card of «Игроки»: which player, period, tab and filters the URL asks for, and every number the
 * card shows, computed from one read of that player's synced documents (through `lib/staff/access.ts`).
 */

export const CARD_PATH = "/staff/players";

export const CARD_TABS = ["profile", "tests", "game", "corr", "wheel", "food", "sleep"] as const;
export type CardTab = (typeof CARD_TABS)[number];
export const CARD_PERIODS = [7, 30, 90] as const;
export type CardPeriod = (typeof CARD_PERIODS)[number];
export const TEST_TYPES = ["all", "cog", "game"] as const;
export type TestType = (typeof TEST_TYPES)[number];
export const GAME_SOURCES = ["all", "faceit"] as const;
export type GameSource = (typeof GAME_SOURCES)[number];

const DEFAULT_PERIOD: CardPeriod = 30;
const DEFAULT_TAB: CardTab = "profile";

/** Everything the card needs from the synced collections, in one `playerDocuments` call. */
export const CARD_COLLECTIONS = [
  "sleep", "nutrition", "wellbeing", "cognitive", "screen-time", "faceit", "player-survey",
] as const satisfies readonly Collection[];

/** The board's thresholds and sizes. */
const HIGH_SCORE = 8;
const SHORT_NIGHT_HOURS = 7;
const SLEEP_ROWS = 10;
const TEST_ROWS = 30;

export interface CardParams {
  /** Always a player of the caller's roster. */
  player: string;
  period: CardPeriod;
  tab: CardTab;
  type: TestType;
  src: GameSource;
  /** A day of the balance wheel; null for «today». */
  day: Day | null;
}

type Raw = string | string[] | undefined;

function firstOf(value: Raw): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

function oneOf<T extends string | number>(options: readonly T[], raw: string | undefined, fallback: T): T {
  return options.find((option) => String(option) === raw) ?? fallback;
}

/** A real calendar day written `YYYY-MM-DD`, else null. */
function validDay(raw: string | undefined): Day | null {
  if (raw === undefined || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return shiftDay(raw, 0) === raw ? raw : null;
}

/**
 * The card's selection from the URL. A `player` that is not on the roster (another team's, a solo player, a
 * removed member, garbage) is the first roster player: no error, and nothing about the id is read or echoed.
 * Null when the team has no players at all. Every other invalid value falls back to its default.
 */
export function parseCardParams(params: SearchParams, rosterIds: readonly string[]): CardParams | null {
  if (rosterIds.length === 0) return null;
  const player = firstOf(params.player);
  return {
    player: player !== undefined && rosterIds.includes(player) ? player : rosterIds[0],
    period: oneOf(CARD_PERIODS, firstOf(params.period), DEFAULT_PERIOD),
    tab: oneOf(CARD_TABS, firstOf(params.tab), DEFAULT_TAB),
    type: oneOf(TEST_TYPES, firstOf(params.type), "all"),
    src: oneOf(GAME_SOURCES, firstOf(params.src), "all"),
    day: validDay(firstOf(params.day)),
  };
}

/** The non-default parts of the selection as query pairs, in URL order; `player` is always there. */
export function cardQuery(params: CardParams): [string, string][] {
  const pairs: [string, string][] = [["player", params.player]];
  if (params.period !== DEFAULT_PERIOD) pairs.push(["period", String(params.period)]);
  if (params.tab !== DEFAULT_TAB) pairs.push(["tab", params.tab]);
  if (params.type !== "all") pairs.push(["type", params.type]);
  if (params.src !== "all") pairs.push(["src", params.src]);
  if (params.day !== null) pairs.push(["day", params.day]);
  return pairs;
}

/** The URL of the card with `patch` applied; defaults are left out. Picking another player drops the wheel's day. */
export function cardHref(params: CardParams, patch: Partial<CardParams> = {}): string {
  const next = { ...params, ...patch, ...(patch.player !== undefined && patch.day === undefined && { day: null }) };
  const query = cardQuery(next)
    .map(([name, value]) => `${name}=${encodeURIComponent(value)}`)
    .join("&");
  return `${CARD_PATH}?${query}`;
}

// ---------------------------------------------------------------- data

export interface KpiNote {
  text: string;
  tone: "up" | "down" | "flat";
}

export interface KpiTile {
  label: string;
  value: string;
  note: KpiNote | null;
}

export interface TestRow {
  date: string;
  test: string;
  score: string;
  state: string;
}

export interface TestsData {
  /** Null when neither series has a day with data. */
  chart: TestsChart | null;
  /** Newest first, at most `TEST_ROWS`. */
  rows: TestRow[];
  /** How many tests match the filter in the period (more than `rows` when the table is cut). */
  total: number;
}

export interface SleepRow {
  date: string;
  start: string;
  end: string;
  duration: string;
  /** Shorter than 7 hours. */
  short: boolean;
}

export interface SleepData {
  summary: string;
  rows: SleepRow[];
}

export type CardData =
  | { kind: "none" }
  | {
      kind: "card";
      params: CardParams;
      player: { id: string; nickname: string; initials: string; faceitUrl: string | null; lastActivity: string };
      rangeLabel: string;
      kpis: KpiTile[];
      tests: TestsData;
      sleep: SleepData;
      wheel: WheelData;
      food: FoodData;
    };

/** Values of the days of the period that have one, oldest first. */
function dataValues(days: readonly Day[], series: DaySeries): number[] {
  return days.flatMap((day) => (typeof series[day] === "number" ? [series[day]] : []));
}

/** The board's tile: the mean of the days with data; the change is second-half mean − first-half mean. */
function dynamicsTile(label: string, days: readonly Day[], series: DaySeries): KpiTile {
  const values = dataValues(days, series);
  const average = mean(values);
  if (average === null) return { label, value: NO_DATA, note: null };
  const half = Math.floor(values.length / 2);
  const before = mean(values.slice(0, half));
  const after = mean(values.slice(half));
  const note: KpiNote =
    before === null || after === null
      ? { text: NO_DATA, tone: "flat" }
      : { text: `${signed1(after - before)} за период`, tone: after >= before ? "up" : "down" };
  return { label, value: whole(average), note };
}

/** «{n} из {days with data}»; a day is high when its DAY MEAN of focus/fatigue reaches 8 (accepted ruling). */
function highTile(label: string, caption: string, days: readonly Day[], series: DaySeries): KpiTile {
  const values = dataValues(days, series);
  const value = values.length === 0 ? NO_DATA : `${values.filter((v) => v >= HIGH_SCORE).length} из ${values.length}`;
  return { label, value, note: { text: caption, tone: "flat" } };
}

const COGNITIVE_TESTS: ReadonlyMap<string, string> = new Map([
  ["reaction", "Скорость реакции"],
  ["goNoGo", "Go / No-Go"],
  ["stroop", "Тест Струпа"],
  ["memory", "Объём памяти"],
  ["aim", "Точность прицела"],
]);
const GAME_TESTS: ReadonlyMap<string, string> = new Map([
  ["beforeTraining", "До тренировки"],
  ["afterTraining", "После тренировки"],
]);

interface TestEvent {
  at: Date;
  name: string;
  /** Null for game tests: they store self-ratings, not a score. */
  score: number | null;
  game: boolean;
}

function testEvents(docs: Documents): TestEvent[] {
  const events: TestEvent[] = [];
  for (const r of records(docs.cognitive, "results")) {
    const at = parseInstant(r.date);
    const name = typeof r.kind === "string" ? COGNITIVE_TESTS.get(r.kind) : undefined;
    if (at && name !== undefined && inRange(r.score, 0, 100)) events.push({ at, name, score: r.score, game: false });
  }
  for (const g of records(docs.cognitive, "gameResults")) {
    const at = parseInstant(g.date);
    const name = typeof g.phase === "string" ? GAME_TESTS.get(g.phase) : undefined;
    if (at && name !== undefined) events.push({ at, name, score: null, game: true });
  }
  return events;
}

function testsData(
  docs: Documents,
  params: CardParams,
  period: Period,
  series: { cog: DaySeries; readiness: DaySeries },
  nickname: string,
  tz: string,
): TestsData {
  const today = period.to;
  const matching = testEvents(docs)
    .filter((e) => (params.type === "all" ? true : params.type === "game" ? e.game : !e.game))
    .filter((e) => {
      const day = dayOf(e.at, tz);
      return day >= period.from && day <= period.to;
    })
    .sort((a, b) => b.at.getTime() - a.at.getTime());
  const rows = matching.slice(0, TEST_ROWS).map((e): TestRow => {
    const day = dayOf(e.at, tz);
    const state = series.readiness[day];
    return {
      date: dayLabel(day, today),
      test: e.name,
      score: e.score === null ? EMPTY_CELL : whole(e.score),
      state: typeof state === "number" ? whole(state) : EMPTY_CELL,
    };
  });
  const score = chartLine(period.days, series.cog);
  const state = chartLine(period.days, series.readiness);
  const hasPoints = [score, state].some((l) => l.line !== "" || l.dots !== "");
  return {
    chart: hasPoints ? { score, state, aria: `Score и Индекс состояния игрока ${nickname} за период` } : null,
    rows,
    total: matching.length,
  };
}

interface Night {
  start: Date;
  end: Date;
}

/** Nights by the day they end (staff zone): the first start and the last end of that day's sleep sessions. */
function nightsByDay(docs: Documents, tz: string): Map<Day, Night> {
  const nights = new Map<Day, Night>();
  for (const s of records(docs.sleep, "sessions")) {
    const start = parseInstant(s.start);
    const end = parseInstant(s.end);
    if (!start || !end || end.getTime() - start.getTime() < MIN_SLEEP_MS) continue;
    const day = dayOf(end, tz);
    const held = nights.get(day);
    nights.set(day, {
      start: held && held.start < start ? held.start : start,
      end: held && held.end > end ? held.end : end,
    });
  }
  return nights;
}

function sleepData(docs: Documents, period: Period, sleep: DaySeries, tz: string): SleepData {
  const hours = dataValues(period.days, sleep);
  const average = mean(hours);
  if (average === null) return { summary: NO_DATA, rows: [] };
  const short = hours.filter((h) => h < SHORT_NIGHT_HOURS).length;
  const today = period.to;
  const nights = nightsByDay(docs, tz);
  const rows = period.days
    .filter((day) => typeof sleep[day] === "number" && nights.has(day))
    .reverse()
    .slice(0, SLEEP_ROWS)
    .map((day): SleepRow => {
      const night = nights.get(day) as Night;
      const from = dayOf(night.start, tz);
      return {
        date: from === day ? dayLabel(day, today) : `${dayLabel(from, today)} → ${dayLabel(day, today).toLowerCase()}`,
        start: clock(night.start, tz),
        end: clock(night.end, tz),
        duration: hoursMinutes(sleep[day]),
        short: sleep[day] < SHORT_NIGHT_HOURS,
      };
    });
  return {
    summary: `Средняя продолжительность ${hoursMinutes(average)} · ночей короче 7 ч: ${short} из ${hours.length}`,
    rows,
  };
}

/**
 * The card for the URL's selection: the roster is the only gate (anyone not on it is replaced by the first
 * roster player), then the player's documents are read once. `today` is the current day in the staff zone.
 */
export async function loadCard(
  deps: Deps,
  ctx: StaffContext,
  raw: SearchParams,
  tz: string = STAFF_TIME_ZONE,
): Promise<CardData> {
  const roster = await teamPlayers(deps, ctx);
  const params = parseCardParams(raw, roster.map((p) => p.id));
  const player = params === null ? undefined : roster.find((p) => p.id === params.player);
  if (params === null || player === undefined) return { kind: "none" };

  const docs = await playerDocuments(deps, ctx, player.id, CARD_COLLECTIONS);
  const now = deps.now();
  const today = dayOf(now, tz);
  const period = periodDays(today, params.period);
  const series = playerSeries({ id: player.id, docs }, period.from, period.to, tz);
  const days = period.days;

  return {
    kind: "card",
    params,
    player: {
      id: player.id,
      nickname: player.nickname,
      initials: initials(player.nickname),
      faceitUrl: player.faceitUrl,
      lastActivity: lastActivityLabel(player.lastActivity, now, tz),
    },
    rangeLabel: `${monthDay(period.from, today)} – ${monthDay(period.to, today)}`,
    kpis: [
      dynamicsTile("Средний score", days, series.cog),
      dynamicsTile("Индекс состояния", days, series.readiness),
      highTile("Фокус high", "дней с фокусом 8+", days, series.focus),
      highTile("Усталость high", "дней с усталостью 8+", days, series.fatigue),
    ],
    tests: testsData(docs, params, period, series, player.nickname, tz),
    sleep: sleepData(docs, period, series.sleep, tz),
    wheel: wheelData(docs, today, params.day, (day) => cardHref(params, { day })),
    food: foodData(docs, player.id, period.from, period.to, tz),
  };
}
