/**
 * What a chart shows when it is hovered, tapped or walked with the arrow keys (`components/staff/ChartHover.tsx`):
 * one point per day, placed by fractions of the chart's box, with the day's values as ready-to-read text.
 */

export interface HoverRow {
  label: string;
  /** Formatted, or «нет данных». */
  value: string;
  color?: string;
  /** Where the marker sits, 0 (top) … 1 (bottom) of the chart; none for a day without a value. */
  y?: number;
}

export interface HoverPoint {
  /** 0 (left edge) … 1 (right edge) of the chart. */
  x: number;
  title: string;
  rows: HoverRow[];
}

/** The index of the point nearest to `fraction` of the width; -1 when there are no points. */
export function nearestPoint(points: readonly HoverPoint[], fraction: number): number {
  let best = -1;
  let distance = Infinity;
  points.forEach((point, i) => {
    const d = Math.abs(point.x - fraction);
    if (d < distance) {
      best = i;
      distance = d;
    }
  });
  return best;
}

/** The point an arrow key moves to: from nothing selected, → starts at the first point and ← at the last. */
export function stepPoint(current: number | null, delta: number, count: number): number | null {
  if (count === 0) return null;
  if (current === null) return delta > 0 ? 0 : count - 1;
  return Math.min(count - 1, Math.max(0, current + delta));
}

/** Rounded to 4 places: these numbers end up in inline styles. */
export function fraction(value: number, whole: number): number {
  return Math.round((value / whole) * 10_000) / 10_000;
}
