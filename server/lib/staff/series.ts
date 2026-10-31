/**
 * Per-day series of one player: what the card's charts and the statistics page draw.
 *
 * Pure functions, no I/O. The Kotlin twin is `core/.../feature/StaffSeries.kt`; both are checked against
 * `test/fixtures/staff-series.json`. Sleep, mood, energy, cognitive index and kilocalories come from plan A's
 * `indexDocuments`/`inputsFromIndex` (so they equal `dailyInputs` and the readiness series equals
 * `readiness(dailyInputs(...))`); screen time, the balance wheel, focus/fatigue and ELO are read here.
 *
 * Days: mood, energy, the balance wheel and screen time are PHONE days (`phoneDayOf`); sleep, cognitive tests,
 * meals and ELO snapshots are real instants, so they fall on the calendar day in the staff zone `tz`.
 * A day without a record has no key: a gap is never a zero.
 */
import {
  dayOf,
  daysEndingAt,
  indexDocuments,
  inRange,
  inputsFromIndex,
  isNumber,
  isRecord,
  mean,
  parseInstant,
  phoneDayOf,
  readiness,
  records,
  shiftDay,
  type Day,
  type Documents,
  type PlayerDocuments,
} from "@/lib/staff/metrics";

/** A value per day; an absent key means no data that day. */
export type DaySeries = Record<Day, number>;

export interface PlayerSeries {
  /** Hours of sleep sessions (at least 60 minutes) that end that day, summed. */
  sleep: DaySeries;
  /** Day mean of the 1..10 mood entries. */
  mood: DaySeries;
  energy: DaySeries;
  /** Day mean of `cognitive.results[].score` (0..100). */
  cog: DaySeries;
  /** Day sum of the meals' kilocalories. */
  kcal: DaySeries;
  /** Hours: `screen-time.entries[].minutes / 60`. */
  screen: DaySeries;
  /** Mean of the 8 wheel values; a missing key counts 5.0, as the app does. */
  balance: DaySeries;
  /** Day mean of `cognitive.gameResults[].focus` (1..10). */
  focus: DaySeries;
  fatigue: DaySeries;
  /** `elo` of the latest `faceit.snapshots[]` entry of the day. */
  elo: DaySeries;
  /** Plan A's `readiness(dailyInputs(...))` for every day that has a readiness input. */
  readiness: DaySeries;
}

export interface Period {
  from: Day;
  to: Day;
  /** Oldest first, ending at `to`. */
  days: Day[];
}

/** `BalanceCategory` keys of the app, in its declaration order. */
const WHEEL_KEYS = [
  "physical", "emotional", "intellectual", "spiritual", "professional", "social", "environment", "financial",
] as const;
/** `BalanceEntry.value`: a key missing from an entry counts as this. */
const WHEEL_DEFAULT = 5;
const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 1440;

/** `days` days ending at `to` (inclusive): `from = to − (days − 1)`. */
export function periodDays(to: Day, days: number): Period {
  if (!Number.isInteger(days) || days < 1) throw new Error(`A period needs a whole number of days, got ${days}`);
  const list = daysEndingAt(to, days);
  return { from: list[0], to, days: list };
}

function push(map: Map<Day, number[]>, day: Day, value: number): void {
  const list = map.get(day);
  if (list) list.push(value);
  else map.set(day, [value]);
}

interface ExtraIndex {
  screenHours: Map<Day, number>;
  balance: Map<Day, number>;
  focus: Map<Day, number[]>;
  fatigue: Map<Day, number[]>;
  elo: Map<Day, number>;
}

/** Reads what `indexDocuments` does not cover; malformed records are skipped, the last record of a day wins. */
function indexExtras(docs: Documents, tz: string): ExtraIndex {
  const extra: ExtraIndex = {
    screenHours: new Map(),
    balance: new Map(),
    focus: new Map(),
    fatigue: new Map(),
    elo: new Map(),
  };

  for (const e of records(docs["screen-time"], "entries")) {
    const at = parseInstant(e.date);
    if (at && inRange(e.minutes, 0, MINUTES_PER_DAY)) extra.screenHours.set(phoneDayOf(at), e.minutes / MINUTES_PER_HOUR);
  }
  for (const e of records(docs.wellbeing, "balance")) {
    const at = parseInstant(e.day);
    if (!at || !isRecord(e.values)) continue;
    const values = e.values;
    let sum = 0;
    for (const key of WHEEL_KEYS) {
      const v = values[key];
      sum += inRange(v, 1, 10) ? v : WHEEL_DEFAULT;
    }
    extra.balance.set(phoneDayOf(at), sum / WHEEL_KEYS.length);
  }
  for (const g of records(docs.cognitive, "gameResults")) {
    const at = parseInstant(g.date);
    if (!at) continue;
    const day = dayOf(at, tz);
    if (inRange(g.focus, 1, 10)) push(extra.focus, day, g.focus);
    if (inRange(g.fatigue, 1, 10)) push(extra.fatigue, day, g.fatigue);
  }
  const latest = new Map<Day, number>(); // instant (ms) of the snapshot now held for the day
  for (const s of records(docs.faceit, "snapshots")) {
    const at = parseInstant(s.date);
    if (!at || !isNumber(s.elo)) continue;
    const day = dayOf(at, tz);
    const held = latest.get(day);
    if (held === undefined || at.getTime() >= held) {
      latest.set(day, at.getTime());
      extra.elo.set(day, s.elo);
    }
  }
  return extra;
}

/**
 * The series of one player for `from..to` inclusive, only days with data. `from` after `to` gives empty series;
 * a malformed day throws.
 */
export function playerSeries(player: PlayerDocuments, from: Day, to: Day, tz: string): PlayerSeries {
  shiftDay(from, 0); // validates both ends
  shiftDay(to, 0);
  const index = indexDocuments(player.docs, tz);
  const extra = indexExtras(player.docs, tz);
  const series: PlayerSeries = {
    sleep: {}, mood: {}, energy: {}, cog: {}, kcal: {}, screen: {}, balance: {}, focus: {}, fatigue: {}, elo: {}, readiness: {},
  };

  for (let day = from; day <= to; day = shiftDay(day, 1)) {
    const inputs = inputsFromIndex(index, day);
    if (inputs.sleepHours !== undefined) series.sleep[day] = inputs.sleepHours;
    if (inputs.mood !== undefined) series.mood[day] = inputs.mood;
    if (inputs.energy !== undefined) series.energy[day] = inputs.energy;
    if (inputs.cog !== undefined) series.cog[day] = inputs.cog;
    if (inputs.kcal !== undefined) series.kcal[day] = inputs.kcal;

    const screen = extra.screenHours.get(day);
    if (screen !== undefined) series.screen[day] = screen;
    const balance = extra.balance.get(day);
    if (balance !== undefined) series.balance[day] = balance;
    const focus = mean(extra.focus.get(day) ?? []);
    if (focus !== null) series.focus[day] = focus;
    const fatigue = mean(extra.fatigue.get(day) ?? []);
    if (fatigue !== null) series.fatigue[day] = fatigue;
    const elo = extra.elo.get(day);
    if (elo !== undefined) series.elo[day] = elo;

    const ready = readiness(inputs);
    if (ready !== null) series.readiness[day] = ready;
  }
  return series;
}
