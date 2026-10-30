/**
 * SVG geometry of the «Обзор» charts, ported from `TeamOverview.dc.html`. Days without data leave a gap:
 * nothing is drawn for them and nothing is interpolated.
 */

import type { MoodCategoryShare } from "@/lib/staff/metrics";

// ---- donut (mood by category)

const RADIUS = 80;
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
const DONUT_GAP = 12;

export interface DonutArc {
  dasharray: string;
  dashoffset: string;
  visible: boolean;
}

/** One arc per category, in order; with no records every arc is hidden and only the track shows. */
export function donutArcs(categories: readonly MoodCategoryShare[]): DonutArc[] {
  const total = categories.reduce((sum, c) => sum + c.count, 0);
  let cumulative = 0;
  return categories.map((c) => {
    if (total === 0 || c.count === 0) {
      return { dasharray: `0 ${CIRCUMFERENCE.toFixed(1)}`, dashoffset: (-cumulative).toFixed(1), visible: false };
    }
    const fraction = c.count / total;
    const length = Math.max(0, fraction * CIRCUMFERENCE - DONUT_GAP);
    const arc = {
      dasharray: `${length.toFixed(1)} ${(CIRCUMFERENCE - length).toFixed(1)}`,
      dashoffset: (-cumulative).toFixed(1),
      visible: true,
    };
    cumulative += fraction * CIRCUMFERENCE;
    return arc;
  });
}

// ---- sleep bars and energy line (viewBox 0 0 640 240, axis 0..10)

/** Centres of the seven day columns. */
export const DAY_X = [78, 162, 246, 330, 414, 498, 582] as const;
const BASELINE = 190;
const UNIT = 16;
const AXIS_MAX = 10;
const BAR_WIDTH = 44;
const BAR_RADIUS = 8;

function roundedBar(x: number, y: number, w: number, h: number, r: number): string {
  const radius = Math.min(r, h / 2);
  return (
    `M${x} ${y + h}V${y + radius}a${radius} ${radius} 0 0 1 ${radius} ${-radius}h${w - 2 * radius}` +
    `a${radius} ${radius} 0 0 1 ${radius} ${radius}V${y + h}Z`
  );
}

/** Sleep bars; hours above the axis are drawn at its top (the label carries the real value). */
export function sleepBarsPath(hours: readonly (number | null)[]): string {
  return hours
    .map((v, i) => {
      if (v === null) return "";
      const h = Math.min(v, AXIS_MAX) * UNIT;
      return roundedBar(DAY_X[i] - BAR_WIDTH / 2, Number((BASELINE - h).toFixed(1)), BAR_WIDTH, Number(h.toFixed(1)), BAR_RADIUS);
    })
    .join("");
}

function energyY(value: number): number {
  return BASELINE - value * UNIT;
}

/** Energy line, broken at days without a value. */
export function energyLinePath(energy: readonly (number | null)[]): string {
  let path = "";
  let pen = false;
  energy.forEach((v, i) => {
    if (v === null) {
      pen = false;
      return;
    }
    path += `${pen ? " L" : path === "" ? "M" : " M"}${DAY_X[i]} ${energyY(v).toFixed(1)}`;
    pen = true;
  });
  return path;
}

export function energyDotsPath(energy: readonly (number | null)[]): string {
  return energy
    .map((v, i) =>
      v === null ? "" : `M${DAY_X[i] - 4.5} ${energyY(v).toFixed(1)}a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0`,
    )
    .join("");
}

/** Left offsets (in percent of the chart) of the weekday labels under the bars. */
export function dayLabelLeft(index: number): string {
  return `${(((78 + 84 * index) / 640) * 100).toFixed(2)}%`;
}

// ---- weekly metric trend (viewBox 0 0 600 140)

export interface Trend {
  line: string;
  area: string;
  dots: string;
  /** True when no day has a value. */
  empty: boolean;
}

interface Point {
  x: number;
  y: number;
}

function circle(p: Point, r: number): string {
  return `M${(p.x - r).toFixed(1)} ${p.y.toFixed(1)}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
}

/**
 * Line and filled area of a 7-day series scaled between its own minimum and maximum, as on the board. Runs of
 * consecutive days are joined; a lone day is a dot; the latest day with a value gets the larger dot.
 */
export function trend(series: readonly (number | null)[]): Trend {
  const known = series.flatMap((v) => (v === null ? [] : [v]));
  if (known.length === 0) return { line: "", area: "", dots: "", empty: true };

  const min = Math.min(...known);
  let max = Math.max(...known);
  if (max - min < 1e-6) max = min + 1;
  const step = series.length > 1 ? 580 / (series.length - 1) : 0;

  const runs: Point[][] = [];
  let open = false;
  series.forEach((v, i) => {
    if (v === null) {
      open = false;
      return;
    }
    const point = { x: 10 + i * step, y: 120 - ((v - min) / (max - min)) * 90 };
    if (open) runs[runs.length - 1].push(point);
    else runs.push([point]);
    open = true;
  });

  const linePath = (run: Point[]): string =>
    run.map((p, i) => `${i === 0 ? "M" : "L"}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(" ");
  const line = runs.map(linePath).join(" ");
  const area = runs
    .filter((run) => run.length > 1)
    .map((run) => `${linePath(run)} L${run[run.length - 1].x.toFixed(1)} 130 L${run[0].x.toFixed(1)} 130 Z`)
    .join(" ");
  const dots = runs
    .map((run, i) =>
      i === runs.length - 1 ? circle(run[run.length - 1], 6) : run.length === 1 ? circle(run[0], 4) : "",
    )
    .join("");
  return { line, area, dots, empty: false };
}
