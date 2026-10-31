import { dayLabel } from "@/lib/staff/card-format";
import { parseMealId } from "@/lib/photos";
import {
  dayOf,
  isNumber,
  mean,
  parseInstant,
  records,
  roundHalfUp,
  type Day,
  type Documents,
} from "@/lib/staff/metrics";
import { NO_DATA } from "@/lib/staff/overview-format";
import { EMPTY_CELL, clock } from "@/lib/staff/players";

/** «Питание» of the player card: the diary of the period and its summary line. */

export const FOOD_ROWS = 30;

export interface FoodRow {
  /** «Сегодня, 13:05», «Вчера, 12:30», «20 окт, 11:15». */
  date: string;
  name: string;
  /** «600 ккал», or «—» when the meal has no valid kilocalories. */
  kcal: string;
  /** The staff photo route of this meal; null without a stored photo (`photoRef`), the table shows a placeholder. */
  photo: string | null;
}

export interface FoodData {
  /** «Записей: {n} · в среднем {X} ккал в день»; the mean is over the days of the period that have meals. */
  summary: string;
  /** Newest first, at most `FOOD_ROWS`. */
  rows: FoodRow[];
  /** Meals of the period (more than `rows` when the table is cut). */
  total: number;
}

interface Meal {
  at: Date;
  day: Day;
  name: string;
  kcal: number | null;
  photo: string | null;
}

/** The URL of the staff photo route; only for a meal that says it has a photo and whose id the store can hold. */
export function photoUrl(playerId: string, mealId: unknown, photoRef: unknown): string | null {
  if (photoRef !== true || typeof mealId !== "string") return null;
  const id = parseMealId(mealId);
  return id === null ? null : `/staff/photos/${playerId}/${id}`;
}

function meals(docs: Documents, playerId: string, from: Day, to: Day, tz: string): Meal[] {
  const found: Meal[] = [];
  for (const m of records(docs.nutrition, "meals")) {
    const at = parseInstant(m.date);
    if (!at) continue;
    const day = dayOf(at, tz);
    if (day < from || day > to) continue;
    found.push({
      at,
      day,
      name: typeof m.name === "string" && m.name.trim() !== "" ? m.name : EMPTY_CELL,
      kcal: isNumber(m.kcal) && m.kcal >= 0 ? m.kcal : null,
      photo: photoUrl(playerId, m.id, m.photoRef),
    });
  }
  return found.sort((a, b) => b.at.getTime() - a.at.getTime());
}

/** The meals of `from..to` (days in the staff zone `tz`) of one player. */
export function foodData(docs: Documents, playerId: string, from: Day, to: Day, tz: string): FoodData {
  const inPeriod = meals(docs, playerId, from, to, tz);
  const dailySums = new Map<Day, number>();
  for (const m of inPeriod) if (m.kcal !== null) dailySums.set(m.day, (dailySums.get(m.day) ?? 0) + m.kcal);
  const average = mean([...dailySums.values()]);
  const averageText = average === null ? NO_DATA : `${roundHalfUp(average)} ккал в день`;
  return {
    summary: `Записей: ${inPeriod.length} · в среднем ${averageText}`,
    rows: inPeriod.slice(0, FOOD_ROWS).map((m) => ({
      date: `${dayLabel(m.day, to)}, ${clock(m.at, tz)}`,
      name: m.name,
      kcal: m.kcal === null ? EMPTY_CELL : `${roundHalfUp(m.kcal)} ккал`,
      photo: m.photo,
    })),
    total: inPeriod.length,
  };
}
