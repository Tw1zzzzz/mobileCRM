/**
 * Staff metrics: the numbers the «Обзор» page shows, computed from the players' synced documents.
 *
 * Pure functions, no I/O. The Kotlin twin is `core/.../feature/StaffMetrics.kt`; both are checked against
 * `test/fixtures/staff-metrics.json`, so a metric has the same value in the panel and in the staff app.
 * Thresholds, categories and the readiness formula are copied from the design boards
 * (`TeamOverview.dc.html`, `Players.dc.html`).
 *
 * Rounding: `roundHalfUp(x) = floor(x + 0.5)` (never banker's rounding) and `round2(x) = roundHalfUp(x * 100) / 100`,
 * identical in Kotlin.
 */

/** A calendar day, `YYYY-MM-DD`, in the staff time zone. */
export type Day = string;

/** Stored documents of one player by collection name (`sleep`, `wellbeing`, `cognitive`, `nutrition`). */
export type Documents = { readonly [collection: string]: unknown };

export interface PlayerDocuments {
  id: string;
  docs: Documents;
}

export interface DailyInputs {
  sleepHours?: number;
  mood?: number;
  energy?: number;
  /** Cognitive index 0..100: average score of that day's tests (`CognitiveScoring` already scores 0..100). */
  cog?: number;
  kcal?: number;
}

export type Pill = "good" | "mid" | "bad";
export type ReadinessStatus = "ready" | "doubt" | "risk";
export type Direction = "up" | "down";

export interface MoodCategory {
  name: string;
  range: string;
  count: number;
}

export interface MoodCategoryShare extends MoodCategory {
  percent: number;
}

export type MetricKey = "readiness" | "cognitive" | "nutrition";

export interface WeeklyMetric {
  key: MetricKey;
  value: number | null;
  previous: number | null;
  delta: number | null;
  direction: Direction | null;
  pill: Pill | null;
  /** One value per day of the window, `null` where nobody has data. */
  series: (number | null)[];
}

export interface OverviewData {
  who: string;
  today: Day;
  /** The 7 days of the window, oldest first, ending today. */
  days: Day[];
  /** Whole team, today; the player filter does not change it. */
  teamReadiness: number | null;
  teamReadinessStatus: ReadinessStatus | null;
  playerReadiness: { id: string; readiness: number | null; status: ReadinessStatus | null }[];
  mood: { count: number; average: number | null; pill: Pill | null; categories: MoodCategoryShare[] };
  sleep: {
    daily: (number | null)[];
    average: number | null;
    pill: Pill | null;
    lowest: { day: Day; hours: number } | null;
  };
  energy: { daily: (number | null)[]; today: number | null; trend: Direction | null };
  metrics: WeeklyMetric[];
}

// ---------------------------------------------------------------- settings

export const DEFAULT_STAFF_TIME_ZONE = "Europe/Moscow";

/** The configured zone (`STAFF_TIME_ZONE`, default Moscow); an invalid name fails loudly. */
export function resolveTimeZone(value: string | undefined): string {
  const zone = value && value.trim() !== "" ? value.trim() : DEFAULT_STAFF_TIME_ZONE;
  try {
    new Intl.DateTimeFormat("en-CA", { timeZone: zone });
  } catch {
    throw new Error(`STAFF_TIME_ZONE is not a valid IANA time zone: ${zone}`);
  }
  return zone;
}

export const STAFF_TIME_ZONE: string = resolveTimeZone(process.env.STAFF_TIME_ZONE);

/** Board `pill(...)` calls and every `metric(...)` call of `TeamOverview.dc.html`. */
export const PILL_THRESHOLDS = {
  mood: { ok: 6.8, mid: 5.2 },
  sleep: { ok: 7.3, mid: 6.6 },
  readiness: { ok: 70, mid: 55 },
  cognitive: { ok: 66, mid: 56 },
  nutrition: { ok: 2100, mid: 1700 },
  // Game metrics: shown from plan C; kept here so the board's numbers live in one place.
  kd: { ok: 1.05, mid: 0.95 },
  winRate: { ok: 55, mid: 45 },
} as const;

/** A sleep session shorter than this is «Неполная» in the app and is not counted as sleep. */
const MIN_SLEEP_MS = 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;
const WINDOW_DAYS = 7;

// ---------------------------------------------------------------- numbers

function roundHalfUp(x: number): number {
  return Math.floor(x + 0.5);
}

function round2(x: number): number {
  return roundHalfUp(x * 100) / 100;
}

function round2OrNull(x: number | null): number | null {
  return x === null ? null : round2(x);
}

function isNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function inRange(value: unknown, min: number, max: number): value is number {
  return isNumber(value) && value >= min && value <= max;
}

function mean(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  let sum = 0;
  for (const v of values) sum += v;
  return sum / values.length;
}

function meanOfKnown(values: readonly (number | null)[]): number | null {
  return mean(values.filter((v): v is number => v !== null));
}

// ---------------------------------------------------------------- readiness, categories, pills

const READINESS_FULL_MAX = 115;

/**
 * Readiness 0..100 (owner's formula): `(sleep − 4.5) / 4 × 30 + mood × 3 + energy × 2.5 + cog × 0.3`. With inputs
 * missing, the sum of the available terms is scaled by `115 / (sum of their maxima)` (sleep 30, mood 30,
 * energy 25, cog 30); nothing available → `null`.
 */
export function readiness(inputs: DailyInputs): number | null {
  const terms: { term: number; max: number }[] = [];
  if (isNumber(inputs.sleepHours)) terms.push({ term: ((inputs.sleepHours - 4.5) / 4) * 30, max: 30 });
  if (isNumber(inputs.mood)) terms.push({ term: inputs.mood * 3, max: 30 });
  if (isNumber(inputs.energy)) terms.push({ term: inputs.energy * 2.5, max: 25 });
  if (isNumber(inputs.cog)) terms.push({ term: inputs.cog * 0.3, max: 30 });
  if (terms.length === 0) return null;

  let sum = 0;
  let max = 0;
  for (const t of terms) {
    sum += t.term;
    max += t.max;
  }
  const scaled = max === READINESS_FULL_MAX ? sum : (sum * READINESS_FULL_MAX) / max;
  return roundHalfUp(Math.min(100, Math.max(0, scaled)));
}

export function readinessStatus(value: number): ReadinessStatus {
  if (value >= PILL_THRESHOLDS.readiness.ok) return "ready";
  if (value >= PILL_THRESHOLDS.readiness.mid) return "doubt";
  return "risk";
}

export function pill(value: number, ok: number, mid: number): Pill {
  if (value >= ok) return "good";
  if (value >= mid) return "mid";
  return "bad";
}

const MOOD_CATEGORIES: readonly { name: string; range: string; has: (v: number) => boolean }[] = [
  { name: "Отличное", range: "8–10", has: (v) => v >= 7.5 },
  { name: "Хорошее", range: "6–7", has: (v) => v >= 5.5 && v < 7.5 },
  { name: "Среднее", range: "4–5", has: (v) => v >= 3.5 && v < 5.5 },
  { name: "Плохое", range: "1–3", has: (v) => v < 3.5 },
];

export function moodCategories(entries: readonly number[]): MoodCategory[] {
  return MOOD_CATEGORIES.map((c) => ({
    name: c.name,
    range: c.range,
    count: entries.filter(c.has).length,
  }));
}

// ---------------------------------------------------------------- days

const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
const DAY_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatterFor(tz: string): Intl.DateTimeFormat {
  let f = formatters.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat("en-CA", { timeZone: tz, year: "numeric", month: "2-digit", day: "2-digit" });
    formatters.set(tz, f);
  }
  return f;
}

/** The calendar day of an instant in the given zone. */
export function dayOf(instant: Date, tz: string): Day {
  const parts = formatterFor(tz).formatToParts(instant);
  const pick = (type: string): string => parts.find((p) => p.type === type)?.value ?? "";
  return `${pick("year")}-${pick("month")}-${pick("day")}`;
}

/** Shifts a `YYYY-MM-DD` day by whole calendar days. */
export function shiftDay(day: Day, delta: number): Day {
  if (!DAY_PATTERN.test(day)) throw new Error(`Not a YYYY-MM-DD day: ${day}`);
  const [y, m, d] = day.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + delta)).toISOString().slice(0, 10);
}

/** `count` days ending at `end`, oldest first. */
function daysEndingAt(end: Day, count: number): Day[] {
  return Array.from({ length: count }, (_, i) => shiftDay(end, i - (count - 1)));
}

function parseInstant(value: unknown): Date | null {
  if (typeof value !== "string" || !ISO_INSTANT.test(value)) return null;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : new Date(ms);
}

// ---------------------------------------------------------------- documents → per-day values

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** The object entries of `doc[key]`; anything else (wrong shape, wrong type) yields nothing. */
function records(doc: unknown, key: string): Record<string, unknown>[] {
  if (!isRecord(doc)) return [];
  const list = doc[key];
  return Array.isArray(list) ? list.filter(isRecord) : [];
}

interface DayIndex {
  sleepMs: Map<Day, number>;
  mood: Map<Day, number[]>;
  energy: Map<Day, number[]>;
  cog: Map<Day, number[]>;
  kcal: Map<Day, number>;
}

function push(map: Map<Day, number[]>, day: Day, value: number): void {
  const list = map.get(day);
  if (list) list.push(value);
  else map.set(day, [value]);
}

function add(map: Map<Day, number>, day: Day, value: number): void {
  map.set(day, (map.get(day) ?? 0) + value);
}

/** Reads the four documents once; every value is validated, anything malformed is skipped. */
function indexDocuments(docs: Documents, tz: string): DayIndex {
  const index: DayIndex = { sleepMs: new Map(), mood: new Map(), energy: new Map(), cog: new Map(), kcal: new Map() };

  for (const s of records(docs.sleep, "sessions")) {
    const start = parseInstant(s.start);
    const end = parseInstant(s.end);
    if (!start || !end) continue;
    const ms = end.getTime() - start.getTime();
    if (ms >= MIN_SLEEP_MS) add(index.sleepMs, dayOf(end, tz), ms);
  }
  for (const e of records(docs.wellbeing, "mood")) {
    const at = parseInstant(e.day);
    if (!at) continue;
    const day = dayOf(at, tz);
    if (inRange(e.mood, 1, 10)) push(index.mood, day, e.mood);
    if (inRange(e.energy, 1, 10)) push(index.energy, day, e.energy);
  }
  for (const r of records(docs.cognitive, "results")) {
    const at = parseInstant(r.date);
    if (at && inRange(r.score, 0, 100)) push(index.cog, dayOf(at, tz), r.score);
  }
  for (const m of records(docs.nutrition, "meals")) {
    const at = parseInstant(m.date);
    if (at && isNumber(m.kcal) && m.kcal >= 0) add(index.kcal, dayOf(at, tz), m.kcal);
  }
  return index;
}

function inputsFromIndex(index: DayIndex, day: Day): DailyInputs {
  const sleepMs = index.sleepMs.get(day);
  const mood = mean(index.mood.get(day) ?? []);
  const energy = mean(index.energy.get(day) ?? []);
  const cog = mean(index.cog.get(day) ?? []);
  const kcal = index.kcal.get(day);
  return {
    ...(sleepMs !== undefined && { sleepHours: round2(sleepMs / HOUR_MS) }),
    ...(mood !== null && { mood: round2(mood) }),
    ...(energy !== null && { energy: round2(energy) }),
    ...(cog !== null && { cog: round2(cog) }),
    ...(kcal !== undefined && { kcal: round2(kcal) }),
  };
}

/**
 * One player's numbers for one day: sleep hours of the sessions that END that day (a night belongs to the
 * wake-up day), mood and energy as the day's average, cognitive index as the average test score, kilocalories
 * as the sum of meals. A value is absent when there is no record for the day.
 */
export function dailyInputs(docs: Documents, day: Day, tz: string): DailyInputs {
  return inputsFromIndex(indexDocuments(docs, tz), day);
}

// ---------------------------------------------------------------- overview

interface PlayerIndex {
  id: string;
  index: DayIndex;
}

/** Mean over players of `pick(player, day)`, per day; players without a value that day are left out. */
function dailySeries(
  players: readonly PlayerIndex[],
  days: readonly Day[],
  pick: (inputs: DailyInputs) => number | undefined | null,
): (number | null)[] {
  return days.map((day) => {
    const values: number[] = [];
    for (const p of players) {
      const v = pick(inputsFromIndex(p.index, day));
      if (v !== undefined && v !== null) values.push(v);
    }
    return mean(values);
  });
}

function weeklyMetric(
  key: MetricKey,
  players: readonly PlayerIndex[],
  days: readonly Day[],
  previousDays: readonly Day[],
  pick: (inputs: DailyInputs) => number | undefined | null,
): WeeklyMetric {
  const series = dailySeries(players, days, pick);
  const value = meanOfKnown(series);
  const previous = meanOfKnown(dailySeries(players, previousDays, pick));
  const both = value !== null && previous !== null;
  const { ok, mid } = PILL_THRESHOLDS[key];
  return {
    key,
    value: round2OrNull(value),
    previous: round2OrNull(previous),
    delta: both ? round2(value - previous) : null,
    direction: both ? (value >= previous ? "up" : "down") : null,
    pill: value === null ? null : pill(value, ok, mid),
    series: series.map(round2OrNull),
  };
}

/**
 * Everything «Обзор» renders. `who` is `"team"` or a player id; the selection feeds the mood, sleep, energy and
 * weekly blocks, while `teamReadiness` and `playerReadiness` always cover the whole team. Players without data
 * are skipped by every mean; a block with no data at all is `null`.
 */
export function teamOverview(
  playersDocs: readonly PlayerDocuments[],
  today: Day,
  tz: string,
  who: string,
): OverviewData {
  const all: PlayerIndex[] = playersDocs.map((p) => ({ id: p.id, index: indexDocuments(p.docs, tz) }));
  const selected = who === "team" ? all : all.filter((p) => p.id === who);
  const days = daysEndingAt(today, WINDOW_DAYS);
  const previousDays = daysEndingAt(shiftDay(today, -WINDOW_DAYS), WINDOW_DAYS);

  const playerReadiness = all.map((p) => {
    const value = readiness(inputsFromIndex(p.index, today));
    return { id: p.id, readiness: value, status: value === null ? null : readinessStatus(value) };
  });
  const teamMean = mean(playerReadiness.flatMap((p) => (p.readiness === null ? [] : [p.readiness])));
  const teamReadiness = teamMean === null ? null : roundHalfUp(teamMean);

  const moodRecords = selected.flatMap((p) => days.flatMap((d) => p.index.mood.get(d) ?? []));
  const moodAverage = mean(moodRecords);
  const categories = moodCategories(moodRecords).map((c) => ({
    ...c,
    percent: moodRecords.length === 0 ? 0 : roundHalfUp((c.count / moodRecords.length) * 100),
  }));

  const sleepDaily = dailySeries(selected, days, (i) => i.sleepHours);
  const sleepAverage = meanOfKnown(sleepDaily);
  let lowestIndex = -1;
  sleepDaily.forEach((hours, i) => {
    const best = lowestIndex < 0 ? null : sleepDaily[lowestIndex];
    if (hours !== null && (best === null || hours < best)) lowestIndex = i;
  });
  const lowestHours = lowestIndex < 0 ? null : sleepDaily[lowestIndex];

  const energyDaily = dailySeries(selected, days, (i) => i.energy);
  const [yesterdayEnergy, todayEnergy] = energyDaily.slice(-2);
  const energyTrend: Direction | null =
    yesterdayEnergy === null || todayEnergy === null ? null : todayEnergy >= yesterdayEnergy ? "up" : "down";

  return {
    who,
    today,
    days,
    teamReadiness,
    teamReadinessStatus: teamReadiness === null ? null : readinessStatus(teamReadiness),
    playerReadiness,
    mood: {
      count: moodRecords.length,
      average: round2OrNull(moodAverage),
      pill: moodAverage === null ? null : pill(moodAverage, PILL_THRESHOLDS.mood.ok, PILL_THRESHOLDS.mood.mid),
      categories,
    },
    sleep: {
      daily: sleepDaily.map(round2OrNull),
      average: round2OrNull(sleepAverage),
      pill: sleepAverage === null ? null : pill(sleepAverage, PILL_THRESHOLDS.sleep.ok, PILL_THRESHOLDS.sleep.mid),
      lowest: lowestHours === null ? null : { day: days[lowestIndex], hours: round2(lowestHours) },
    },
    energy: { daily: energyDaily.map(round2OrNull), today: round2OrNull(todayEnergy), trend: energyTrend },
    metrics: [
      weeklyMetric("readiness", selected, days, previousDays, (i) => readiness(i)),
      weeklyMetric("cognitive", selected, days, previousDays, (i) => i.cog),
      weeklyMetric("nutrition", selected, days, previousDays, (i) => i.kcal),
    ],
  };
}
