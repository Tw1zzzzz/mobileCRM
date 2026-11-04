import { dayLabel } from "@/lib/staff/card-format";
import { fraction, type HoverPoint } from "@/lib/staff/chart-hover";
import type { Day } from "@/lib/staff/metrics";
import { DAY_X, SLEEP_CHART, TREND_CHART, energyY, trendX, trendYs } from "@/lib/staff/overview-charts";
import { NO_DATA, fmt1, hm, weekdayCapital } from "@/lib/staff/overview-format";

/** What hovering the «Обзор» charts shows: one point per day of the week, «Пн · 13 окт» and the day's values. */

export const SLEEP_COLOR = "#3FD0FF";
export const ENERGY_COLOR = "#FFAA3C";

/** «Пн · Вчера», «Ср · 15 окт». */
export function dayTitle(day: Day, today: Day): string {
  return `${weekdayCapital(day)} · ${dayLabel(day, today)}`;
}

/** «Сон и энергия»: each day's sleep (marker at the top of its bar) and energy. */
export function sleepEnergyPoints(
  days: readonly Day[],
  sleep: readonly (number | null)[],
  energy: readonly (number | null)[],
  today: Day,
): HoverPoint[] {
  return days.map((day, i) => {
    const hours = sleep[i] ?? null;
    const level = energy[i] ?? null;
    return {
      x: fraction(DAY_X[i], SLEEP_CHART.width),
      title: dayTitle(day, today),
      rows: [
        { label: "Сон", value: hours === null ? NO_DATA : hm(hours), color: SLEEP_COLOR, y: hours === null ? undefined : fraction(energyY(hours), SLEEP_CHART.height) },
        { label: "Энергия", value: level === null ? NO_DATA : fmt1(level), color: ENERGY_COLOR, y: level === null ? undefined : fraction(energyY(level), SLEEP_CHART.height) },
      ],
    };
  });
}

/** A weekly metric's trend: each day's value, formatted the card's way. */
export function trendPoints(
  days: readonly Day[],
  series: readonly (number | null)[],
  today: Day,
  label: string,
  format: (value: number) => string,
  color: string,
): HoverPoint[] {
  const ys = trendYs(series);
  return days.map((day, i) => {
    const value = series[i] ?? null;
    const y = ys[i] ?? null;
    return {
      x: fraction(trendX(i, days.length), TREND_CHART.width),
      title: dayTitle(day, today),
      rows: [{ label, value: value === null ? NO_DATA : format(value), color, y: y === null ? undefined : fraction(y, TREND_CHART.height) }],
    };
  });
}
