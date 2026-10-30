import type { Day, Direction, Pill } from "@/lib/staff/metrics";

/** Text formats of the board (`TeamOverview.dc.html`): comma decimals, «7ч 09м», plural forms, arrows. */

export const NO_DATA = "нет данных";

const WEEKDAYS = ["вс", "пн", "вт", "ср", "чт", "пт", "сб"] as const;
const NBSP = " ";

export function fmt1(value: number): string {
  return value.toFixed(1).replace(".", ",");
}

/** Hours as «7ч 09м». */
export function hm(hours: number): string {
  let h = Math.floor(hours);
  let m = Math.round((hours - h) * 60);
  if (m === 60) {
    h += 1;
    m = 0;
  }
  return `${h}ч ${String(m).padStart(2, "0")}м`;
}

/** Whole number; a thousands separator for kilocalories. */
export function whole(value: number): string {
  return String(Math.round(value));
}

export function kcal(value: number): string {
  return whole(value).replace(/\B(?=(\d{3})+(?!\d))/g, NBSP);
}

export function plural(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  return m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20) ? few : many;
}

export function records(n: number): string {
  return `${n} ${plural(n, "запись", "записи", "записей")}`;
}

export function pillLabel(pill: Pill): string {
  return pill === "good" ? "Отлично" : pill === "mid" ? "Средне" : "Плохо";
}

export function arrow(direction: Direction): string {
  return direction === "up" ? "↑" : "↓";
}

/** Two-letter weekday of a `YYYY-MM-DD` day: «пн». */
export function weekdayShort(day: Day): string {
  const [y, m, d] = day.split("-").map(Number);
  return WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
}

export function weekdayCapital(day: Day): string {
  const w = weekdayShort(day);
  return w.charAt(0).toUpperCase() + w.slice(1);
}
