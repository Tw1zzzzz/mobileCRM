import { STAFF_TIME_ZONE, dayOf, shiftDay, type Day } from "@/lib/staff/metrics";
import { plural } from "@/lib/staff/overview-format";

/** Wording of the lineups board (`Lineups.dc.html`): «сегодня», «вчера», «3 окт», «12 записей на Mirage». */

const MONTHS = ["янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"] as const;

/** «сегодня», «вчера» or «3 окт» (with the year when it is not this one) for the day `created` falls on. */
export function lineupWhen(created: Date, today: Day, tz: string = STAFF_TIME_ZONE): string {
  const day = dayOf(created, tz);
  if (day === today) return "сегодня";
  if (day === shiftDay(today, -1)) return "вчера";
  const [year, month, date] = day.split("-").map(Number);
  const label = `${date} ${MONTHS[month - 1]}`;
  return year === Number(today.slice(0, 4)) ? label : `${label} ${year}`;
}

/** «1 запись» / «3 записи» / «12 записей»; `mapName` adds « на Mirage». */
export function foundLabel(count: number, mapName: string | null, more = false): string {
  const noun = plural(count, "запись", "записи", "записей");
  return `${count}${more ? "+" : ""} ${noun}${mapName ? ` на ${mapName}` : ""}`;
}
