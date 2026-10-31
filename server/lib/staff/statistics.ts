/**
 * Correlation math of the staff «Статистика» page and the card's correlations: Pearson over per-day series,
 * the board's strength labels, the median split behind the «Что это значит» sentence, and the «key links».
 *
 * Pure functions, no I/O. The Kotlin twin is `core/.../feature/StaffStatistics.kt` (its `pearson` is the app's
 * `StatisticsMath.pearson`); both are checked against `test/fixtures/staff-statistics.json`. Nothing here rounds:
 * the UI shows r with two decimals (`round2`), the math keeps the raw value.
 */
import type { DaySeries } from "@/lib/staff/series";

export interface PairedDays {
  xs: number[];
  ys: number[];
}

/** Days present in both series, in ascending day order (ISO day strings sort chronologically). */
export function pairByDay(a: DaySeries, b: DaySeries): PairedDays {
  const days = Object.keys(a)
    .filter((day) => Object.prototype.hasOwnProperty.call(b, day))
    .sort();
  return { xs: days.map((day) => a[day]), ys: days.map((day) => b[day]) };
}

/**
 * Pearson correlation of paired samples; null for a size mismatch, fewer than 3 pairs or zero variance.
 * The same algorithm as the app's `StatisticsMath.pearson` (mean first, plain double sums).
 */
export function pearson(xs: number[], ys: number[]): number | null {
  if (xs.length !== ys.length || xs.length < 3) return null;
  const meanX = sum(xs) / xs.length;
  const meanY = sum(ys) / ys.length;
  let numerator = 0;
  let sumX = 0;
  let sumY = 0;
  for (let i = 0; i < xs.length; i++) {
    const dx = xs[i] - meanX;
    const dy = ys[i] - meanY;
    numerator += dx * dy;
    sumX += dx * dx;
    sumY += dy * dy;
  }
  const denominator = Math.sqrt(sumX * sumY);
  if (denominator <= 0) return null;
  return numerator / denominator;
}

export type Direction = "direct" | "inverse";

export interface Strength {
  /** «Сильная» / «Заметная» / «Слабая» / «Связи почти нет», or «мало данных» when r is null. */
  label: string;
  /** Set when |r| ≥ 0.2: the UI appends « прямая связь» / « обратная связь». */
  direction: Direction | null;
}

/** The board's labels (Statistics.dc.html, `strength`), not the app's 0.6 / 0.3 ones. */
export function strength(r: number | null): Strength {
  if (r === null) return { label: "мало данных", direction: null };
  const abs = Math.abs(r);
  const label = abs >= 0.7 ? "Сильная" : abs >= 0.4 ? "Заметная" : abs >= 0.2 ? "Слабая" : "Связи почти нет";
  const direction: Direction | null = abs < 0.2 ? null : r > 0 ? "direct" : "inverse";
  return { label, direction };
}

/** r of two per-day series over the days both have. */
export function correlate(a: DaySeries, b: DaySeries): number | null {
  const { xs, ys } = pairByDay(a, b);
  return pearson(xs, ys);
}

/** Team mode: every player's paired days go into one sample. */
export function correlatePooled(pairs: readonly { a: DaySeries; b: DaySeries }[]): number | null {
  const xs: number[] = [];
  const ys: number[] = [];
  for (const pair of pairs) {
    const paired = pairByDay(pair.a, pair.b);
    xs.push(...paired.xs);
    ys.push(...paired.ys);
  }
  return pearson(xs, ys);
}

export interface MedianSplit {
  median: number;
  /** Mean of ys on the days where xs ≥ median. */
  hiMean: number;
  /** Mean of ys on the other days. */
  loMean: number;
}

/**
 * The board's example sentence: median = sorted[floor(n/2)], «выше обычного» is xs ≥ median.
 * Null for a size mismatch, fewer than 3 pairs, or when either side is empty.
 */
export function medianSplit(xs: number[], ys: number[]): MedianSplit | null {
  const n = xs.length;
  if (n !== ys.length || n < 3) return null;
  const median = [...xs].sort((u, v) => u - v)[Math.floor(n / 2)];
  const hi: number[] = [];
  const lo: number[] = [];
  xs.forEach((x, i) => (x >= median ? hi : lo).push(ys[i]));
  if (hi.length === 0 || lo.length === 0) return null;
  return { median, hiMean: sum(hi) / hi.length, loMean: sum(lo) / lo.length };
}

export type MetricGroup = "state" | "game";

export interface LinkMetric {
  id: string;
  group: MetricGroup;
}

/** r per pair of metric ids; either triangle may be filled, null or a missing entry means no r. */
export type CorrelationMatrix = Record<string, Record<string, number | null | undefined>>;

export interface KeyLink {
  /** The state metric when the pair is cross; otherwise the earlier of the two in `metrics`. */
  a: string;
  b: string;
  r: number;
  /** One state metric and one game metric. */
  cross: boolean;
}

export const KEY_LINK_COUNT = 3;

function matrixR(matrix: CorrelationMatrix, a: string, b: string): number | null {
  return matrix[a]?.[b] ?? matrix[b]?.[a] ?? null;
}

/**
 * The «key links»: top 3 by |r| among the cross (state × game) pairs of the selected metrics when any cross pair has
 * an r, otherwise among all pairs with an r. A pair without r is never a link; a game-first cross pair comes back
 * state-first; equal |r| keep the order of `metrics`.
 */
export function keyLinks(metrics: readonly LinkMetric[], matrix: CorrelationMatrix): KeyLink[] {
  const all: KeyLink[] = [];
  for (let i = 0; i < metrics.length; i++) {
    for (let j = i + 1; j < metrics.length; j++) {
      const r = matrixR(matrix, metrics[i].id, metrics[j].id);
      if (r === null) continue;
      const cross = metrics[i].group !== metrics[j].group;
      const swap = cross && metrics[i].group === "game";
      all.push({ a: swap ? metrics[j].id : metrics[i].id, b: swap ? metrics[i].id : metrics[j].id, r, cross });
    }
  }
  const cross = all.filter((link) => link.cross);
  const pool = cross.length > 0 ? cross : all;
  return [...pool].sort((p, q) => Math.abs(q.r) - Math.abs(p.r)).slice(0, KEY_LINK_COUNT);
}

export interface StatMetric {
  id: "mood" | "energy" | "sleep" | "screen" | "cog" | "balance" | "kcal" | "elo" | "kd" | "adr" | "wr";
  /** Picker / title label, verbatim from the board's MET table. */
  label: string;
  /** Lower-case name for the example sentence. */
  lower: string;
  group: MetricGroup;
  /** Suffix of a shown value; empty when the value has none. */
  unit: string;
}

/** The board's 12 metrics minus KAST (Faceit cannot give it). */
export const STAT_METRICS: readonly StatMetric[] = [
  { id: "mood", label: "Настроение", lower: "настроение", group: "state", unit: "" },
  { id: "energy", label: "Энергия", lower: "энергия", group: "state", unit: "" },
  { id: "sleep", label: "Сон", lower: "сон", group: "state", unit: "ч" },
  { id: "screen", label: "Экранное время", lower: "экранное время", group: "state", unit: "ч" },
  { id: "cog", label: "Индекс когн. формы", lower: "индекс когнитивной формы", group: "state", unit: "" },
  { id: "balance", label: "Баланс жизни", lower: "баланс жизни", group: "state", unit: "" },
  { id: "kcal", label: "Калорийность", lower: "калорийность", group: "state", unit: "ккал" },
  { id: "elo", label: "Текущий ELO", lower: "ELO", group: "game", unit: "" },
  { id: "kd", label: "K/D", lower: "K/D", group: "game", unit: "" },
  { id: "adr", label: "ADR", lower: "ADR", group: "game", unit: "" },
  { id: "wr", label: "Win Rate", lower: "win rate", group: "game", unit: "%" },
];

function sum(values: readonly number[]): number {
  let total = 0;
  for (const v of values) total += v;
  return total;
}
