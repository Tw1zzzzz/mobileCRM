/**
 * SVG geometry of the card's «Тесты» chart, ported from `Players.dc.html` (viewBox 900×220, x from 40 to 890,
 * y = 180 − v·1.6 for v in 0..100). Only days with data are points: consecutive days are joined, a gap breaks the
 * line and nothing is interpolated; a lone day is a dot.
 */
import type { Day } from "@/lib/staff/metrics";
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
}

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
