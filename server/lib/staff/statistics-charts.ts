/**
 * SVG geometry of the «Статистика» small multiples, ported from `Statistics.dc.html` (viewBox 300×90, one point
 * per day of the period, min–max scaled: `y = 82 − (v − min) / span · 74`, span 1 when the series is flat).
 * A day without data leaves a gap: consecutive days are joined, a gap breaks the line, a lone day is a dot,
 * and nothing is interpolated.
 */

const WIDTH = 300;
const BOTTOM = 82;
const HEIGHT = 74;

export interface Sparkline {
  /** `M… L…` path of the runs of two or more consecutive days; empty when there is none. */
  line: string;
  /** Zero-length segments (drawn as round dots by the stroke cap) for days with no neighbour. */
  dots: string;
}

const num = (value: number): string => value.toFixed(1);

/** The line of one value per day (oldest first); `null` is a day without data. */
/** The chart's viewBox width and height. */
export const SPARKLINE_CHART = { width: WIDTH, height: 90 } as const;

/** The x of day `index` of `count`. */
export function sparklineX(index: number, count: number): number {
  return count === 1 ? WIDTH / 2 : (index * WIDTH) / (count - 1);
}

/** The y of each value, min–max scaled; null for a day without data. */
export function sparklineYs(values: readonly (number | null)[]): (number | null)[] {
  const present = values.filter((v): v is number => v !== null);
  if (present.length === 0) return values.map(() => null);
  const min = Math.min(...present);
  const span = Math.max(...present) - min || 1;
  return values.map((value) => (value === null ? null : BOTTOM - ((value - min) / span) * HEIGHT));
}

export function sparkline(values: readonly (number | null)[]): Sparkline {
  const ys = sparklineYs(values);
  if (ys.every((y) => y === null)) return { line: "", dots: "" };

  const runs: string[][] = [];
  let open = false;
  ys.forEach((y, i) => {
    if (y === null) {
      open = false;
      return;
    }
    const point = `${num(sparklineX(i, values.length))} ${num(y)}`;
    if (open) runs[runs.length - 1].push(point);
    else runs.push([point]);
    open = true;
  });
  return {
    line: runs
      .filter((run) => run.length > 1)
      .map((run) => run.map((p, i) => `${i === 0 ? "M" : "L"}${p}`).join(" "))
      .join(" "),
    dots: runs
      .filter((run) => run.length === 1)
      .map((run) => `M${run[0]}h0`)
      .join(" "),
  };
}
