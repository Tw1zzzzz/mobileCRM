import { parseMealId } from "@/lib/photos";
import {
  dayOf,
  isNumber,
  isRecord,
  parseInstant,
  records,
  roundHalfUp,
  type Day,
} from "@/lib/staff/metrics";
import { EMPTY_CELL } from "@/lib/staff/players";

/** What «Питание команды» reads out of one player's `nutrition` document: meals, goals and the water of a day. */

export type MealSource = "photo" | "manual" | "quick";

export interface Meal {
  /** The id as the app wrote it (UUID in practice); kept as is, compared case-insensitively. */
  id: string;
  playerId: string;
  nickname: string;
  at: Date;
  /** The calendar day of `at` in the staff zone. */
  day: Day;
  name: string;
  kcal: number | null;
  protein: number | null;
  fat: number | null;
  carbs: number | null;
  /** The staff photo route of the meal; only for `photoRef: true` and an id the photo store can hold. */
  photo: string | null;
  source: MealSource | null;
}

/** The app's defaults (`NutritionGoals`): used when the player's document has no valid value. */
export const DEFAULT_KCAL_GOAL = 2400;
export const DEFAULT_WATER_GOAL_ML = 2500;

export interface Goals {
  kcal: number;
  waterMl: number;
}

const SOURCES: readonly MealSource[] = ["photo", "manual", "quick"];

function amount(value: unknown): number | null {
  return isNumber(value) && value >= 0 ? value : null;
}

/** The staff photo URL of a meal that says it has a photo and whose id the photo store can hold. */
function photoOf(playerId: string, id: string, photoRef: unknown): string | null {
  if (photoRef !== true) return null;
  const guid = parseMealId(id);
  return guid === null ? null : `/staff/photos/${playerId}/${guid}`;
}

/**
 * The meals of one player up to and including `today`, in the staff zone. Entries without a usable id or instant
 * are skipped; a missing number stays null (never 0, never invented).
 */
export function mealsOf(
  nutrition: unknown,
  player: { id: string; nickname: string },
  today: Day,
  tz: string,
): Meal[] {
  const found: Meal[] = [];
  for (const m of records(nutrition, "meals")) {
    const at = parseInstant(m.date);
    if (!at || typeof m.id !== "string" || m.id === "") continue;
    const day = dayOf(at, tz);
    if (day > today) continue;
    found.push({
      id: m.id,
      playerId: player.id,
      nickname: player.nickname,
      at,
      day,
      name: typeof m.name === "string" && m.name.trim() !== "" ? m.name.trim() : EMPTY_CELL,
      kcal: amount(m.kcal),
      protein: amount(m.protein),
      fat: amount(m.fat),
      carbs: amount(m.carbs),
      photo: photoOf(player.id, m.id, m.photoRef),
      source: SOURCES.find((s) => s === m.source) ?? null,
    });
  }
  return found;
}

function positive(value: unknown, fallback: number): number {
  return isNumber(value) && value > 0 ? value : fallback;
}

/** The player's goals, each falling back to the app's default when absent or not a positive number. */
export function goalsOf(nutrition: unknown): Goals {
  const goals = isRecord(nutrition) && isRecord(nutrition.goals) ? nutrition.goals : {};
  return {
    kcal: positive(goals.kcal, DEFAULT_KCAL_GOAL),
    waterMl: positive(goals.waterMilliliters, DEFAULT_WATER_GOAL_ML),
  };
}

/** The key of `nutrition.water` for a day: the phone's `Y-M-D` without zero padding («2026-10-5»). */
export function waterKey(day: Day): string {
  const [y, m, d] = day.split("-").map(Number);
  return `${y}-${m}-${d}`;
}

/** Millilitres drunk on `day`; null when the player logged none (the map has no such key or a bad value). */
export function waterMl(nutrition: unknown, day: Day): number | null {
  if (!isRecord(nutrition) || !isRecord(nutrition.water)) return null;
  return amount(nutrition.water[waterKey(day)]);
}

/** A sum of the known amounts, rounded half up. */
export function total(values: readonly (number | null)[]): number {
  return roundHalfUp(values.reduce<number>((sum, v) => sum + (v ?? 0), 0));
}
