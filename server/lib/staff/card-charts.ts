/**
 * SVG geometry of the card's «Тесты» chart, ported from `Players.dc.html` (viewBox 900×220, x from 40 to 890,
 * y = 180 − v·1.6 for v in 0..100). Only days with data are points: consecutive days are joined, a gap breaks the
 * line and nothing is interpolated; a lone day is a dot.
 */
import { dayLabel } from "@/lib/staff/card-format";
import { fraction, type HoverPoint } from "@/lib/staff/chart-hover";
import type { Day } from "@/lib/staff/metrics";
import { NO_DATA, whole } from "@/lib/staff/overview-format";
import type { DaySeries } from "@/lib/staff/series";

const LEFT = 40;
const WIDTH = 850;
const MIDDLE = 465;
const BASELINE = 180;
const SCALE = 1.6;
const DOT_RADIUS = 3.5;

export interface ChartLine {
  /** `M… L…` path of the runs of two or more consecutive days; empty when there is none. */
  line: string;
  /** Path of one small circle per lone day. */
  dots: string;
}

export interface TestsChart {
  score: ChartLine;
  state: ChartLine;
  aria: string;
  /** One per day of the period, for the chart's hover tooltip. */
  points: HoverPoint[];
}

export const SCORE_COLOR = "#3fd0ff";
export const STATE_COLOR = "#ffaa3c";
const VIEW_WIDTH = 900;
const VIEW_HEIGHT = 220;

const num = (value: number): string => String(Number(value.toFixed(1)));

function x(index: number, count: number): number {
  return count === 1 ? MIDDLE : LEFT + (index * WIDTH) / (count - 1);
}

function y(value: number): number {
  return BASELINE - Math.min(100, Math.max(0, value)) * SCALE;
}

interface Point {
  x: number;
  y: number;
}

function circle(p: Point): string {
  const r = DOT_RADIUS;
  return `M${num(p.x - r)} ${num(p.y)}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
}

/** The line of one series over the period's days (oldest first). */
export function chartLine(days: readonly Day[], series: DaySeries): ChartLine {
  const runs: Point[][] = [];
  let open = false;
  days.forEach((day, i) => {
    const value = series[day];
    if (typeof value !== "number") {
      open = false;
      return;
    }
    const point = { x: x(i, days.length), y: y(value) };
    if (open) runs[runs.length - 1].push(point);
    else runs.push([point]);
    open = true;
  });
  return {
    line: runs
      .filter((run) => run.length > 1)
      .map((run) => run.map((p, i) => `${i === 0 ? "M" : "L"}${num(p.x)} ${num(p.y)}`).join(" "))
      .join(" "),
    dots: runs
      .filter((run) => run.length === 1)
      .map((run) => circle(run[0]))
      .join(""),
  };
}

/** Each day of the period: «Вчера» / «15 окт» and that day's Score and state index. */
export function testsPoints(days: readonly Day[], score: DaySeries, state: DaySeries, today: Day): HoverPoint[] {
  const row = (label: string, series: DaySeries, color: string) => (day: Day) => {
    const value = series[day];
    const known = typeof value === "number";
    return { label, value: known ? whole(value) : NO_DATA, color, y: known ? fraction(y(value), VIEW_HEIGHT) : undefined };
  };
  const scoreRow = row("Score", score, SCORE_COLOR);
  const stateRow = row("Индекс состояния", state, STATE_COLOR);
  return days.map((day, i) => ({
    x: fraction(x(i, days.length), VIEW_WIDTH),
    title: dayLabel(day, today),
    rows: [scoreRow(day), stateRow(day)],
  }));
}
