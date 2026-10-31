import { MONTHS } from "@/lib/staff/players";
import { roundHalfUp, shiftDay, type Day } from "@/lib/staff/metrics";
import { fmt1 } from "@/lib/staff/overview-format";

/** Text formats of the player card (`Players.dc.html`): day labels, «7 ч 05 мин», signed deltas, initials. */

/** «3 окт»; the year is added when it is not the current one. */
export function monthDay(day: Day, today: Day): string {
  const [year, month, date] = day.split("-").map(Number);
  const sameYear = year === Number(today.slice(0, 4));
  return `${date} ${MONTHS[month - 1]}${sameYear ? "" : ` ${year}`}`;
}

/** The board's `dl(i)`: «Сегодня», «Вчера» or «3 окт». */
export function dayLabel(day: Day, today: Day): string {
  if (day === today) return "Сегодня";
  if (day === shiftDay(today, -1)) return "Вчера";
  return monthDay(day, today);
}

/** The board's `hm(h)`: «7 ч 05 мин». */
export function hoursMinutes(hours: number): string {
  let h = Math.floor(hours);
  let m = roundHalfUp((hours - h) * 60);
  if (m === 60) {
    h += 1;
    m = 0;
  }
  return `${h} ч ${String(m).padStart(2, "0")} мин`;
}

/** The board's `sgn(v, 1)` with a comma: «+1,2», «−0,5» (U+2212), «±0,0». */
export function signed1(value: number): string {
  const rounded = roundHalfUp(value * 10) / 10;
  const sign = rounded > 0 ? "+" : rounded < 0 ? "−" : "±";
  return `${sign}${fmt1(Math.abs(rounded))}`;
}

/** The avatar's two letters. */
export function initials(nickname: string): string {
  return Array.from(nickname).slice(0, 2).join("").toUpperCase();
}
