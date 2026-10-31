import type { Deps } from "@/lib/deps";
import { parseMealId } from "@/lib/photos";
import { playerDocuments, rosterMembers, type StaffContext } from "@/lib/staff/access";
import { initials } from "@/lib/staff/card-format";
import { STAFF_TIME_ZONE, dayOf, roundHalfUp, shiftDay, type Day } from "@/lib/staff/metrics";
import {
  goalsOf,
  mealsOf,
  total,
  waterMl,
  type Goals,
  type Meal,
  type MealSource,
} from "@/lib/staff/nutrition-meals";
import type { SearchParams } from "@/lib/staff/overview";
import { NO_DATA, fmt1, kcal, plural, records } from "@/lib/staff/overview-format";
import { EMPTY_CELL, clock } from "@/lib/staff/players";

/**
 * «Питание команды»: the read-only food feed of the team (`/staff/players?section=nutrition&who=&days=&photos=&meal=`)
 * and its «Сводка за день». Every number comes from the players' synced `nutrition` documents, read through
 * `lib/staff/access.ts`; a player without meals has none, nothing is invented.
 */

export const NUTRITION_PATH = "/staff/players";
export const NUTRITION_DAYS = [1, 3, 7] as const;
export type NutritionDays = (typeof NUTRITION_DAYS)[number];
export const ALL_PLAYERS = "all";
/** At most this many players are read (each is a whole nutrition document). */
export const MAX_PLAYERS = 12;
const READ_CONCURRENCY = 4;
const DEFAULT_DAYS: NutritionDays = 3;

const MONTHS_FULL = [
  "января", "февраля", "марта", "апреля", "мая", "июня",
  "июля", "августа", "сентября", "октября", "ноября", "декабря",
] as const;
const WEEKDAYS_FULL = ["воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота"] as const;

const SOURCE_PILL: Record<MealSource, { label: string; tone: "ok" | "mute" }> = {
  photo: { label: "AI-анализ фото", tone: "ok" },
  manual: { label: "Вручную", tone: "mute" },
  quick: { label: "Быстрое добавление", tone: "mute" },
};

export interface NutritionParams {
  /** `"all"` or the id of a player shown. */
  who: string;
  days: NutritionDays;
  photos: boolean;
  /** A meal id (lower case) whose dialog was asked for; whether it opens is decided by the loader. */
  meal: string | null;
}

type Raw = string | string[] | undefined;

function firstOf(value: Raw): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export function isNutritionSection(params: SearchParams): boolean {
  return firstOf(params.section) === "nutrition";
}

/**
 * The selection from the URL. Anything invalid falls back to its default without an error: a `who` that is not
 * one of `playerIds` is «all», `days` is 1, 3 or 7, `meal` must at least be a GUID.
 */
export function parseNutritionParams(params: SearchParams, playerIds: readonly string[]): NutritionParams {
  const who = firstOf(params.who);
  const days = NUTRITION_DAYS.find((d) => String(d) === firstOf(params.days)) ?? DEFAULT_DAYS;
  const meal = firstOf(params.meal);
  return {
    who: who !== undefined && playerIds.includes(who) ? who : ALL_PLAYERS,
    days,
    photos: firstOf(params.photos) === "1",
    meal: meal === undefined ? null : (parseMealId(meal)?.toLowerCase() ?? null),
  };
}

/** The URL of the section with `patch` applied; defaults are left out. Pass `meal: null` for a link that closes the dialog. */
export function nutritionHref(params: NutritionParams, patch: Partial<NutritionParams> = {}): string {
  const next: NutritionParams = { ...params, ...patch };
  const pairs: [string, string][] = [["section", "nutrition"]];
  if (next.who !== ALL_PLAYERS) pairs.push(["who", next.who]);
  if (next.days !== DEFAULT_DAYS) pairs.push(["days", String(next.days)]);
  if (next.photos) pairs.push(["photos", "1"]);
  if (next.meal !== null) pairs.push(["meal", next.meal]);
  return `${NUTRITION_PATH}?${pairs.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&")}`;
}

// ---------------------------------------------------------------- shape

export interface MealCard {
  /** Unique within the feed (React key): meal ids are chosen by the players and may repeat across players. */
  key: string;
  nickname: string;
  name: string;
  /** Kilocalories as a number, or «—». */
  kcal: string;
  /** «13:30» in the staff zone. */
  time: string;
  /** The staff photo route; null shows the «без фото» placeholder. */
  photo: string | null;
  /** Opens the dialog; null for a meal whose id is not a UUID or is shared by several visible meals. */
  href: string | null;
}

export interface DayGroup {
  /** «Сегодня, 31 октября, пятница». */
  label: string;
  /** «3 записи». */
  sub: string;
  meals: MealCard[];
}

export interface SummaryRow {
  id: string;
  nickname: string;
  initials: string;
  /** Kilocalories of today's meals. */
  kcal: string;
  /** The player's goal, e.g. «2 400». */
  goal: string;
  /** «2 приёма пищи». */
  meals: string;
  /** The bar's width, 0..100. */
  fill: number;
  /** Fewer than two meals: the bar is drawn warm. */
  warm: boolean;
  macros: [string, string, string];
  /** «Вода 1,8 / 2,5 л» or «Вода нет данных». */
  water: string;
  status: { label: string; tone: "ok" | "mid" | "bad" };
  selected: boolean;
  /** Shows only this player, or all again when this player is already selected. */
  href: string;
}

export interface DishDialog {
  nickname: string;
  source: { label: string; tone: "ok" | "mute" } | null;
  name: string;
  /** «Сегодня, 13:30». */
  when: string;
  photo: string | null;
  tiles: { label: string; value: string }[];
  closeHref: string;
}

export interface NutritionData {
  params: NutritionParams;
  /** The players read, longest-standing first (at most `MAX_PLAYERS`). */
  players: { id: string; nickname: string }[];
  /** Players of the roster beyond `MAX_PLAYERS`. */
  hidden: number;
  /** «5 записей · 2 с фото[ · vega]». */
  countText: string;
  groups: DayGroup[];
  summarySub: string;
  summary: SummaryRow[];
  dialog: DishDialog | null;
}

// ---------------------------------------------------------------- text

function dayParts(day: Day): { d: number; month: string; weekday: string } {
  const [y, m, d] = day.split("-").map(Number);
  return { d, month: MONTHS_FULL[m - 1], weekday: WEEKDAYS_FULL[new Date(Date.UTC(y, m - 1, d)).getUTCDay()] };
}

/** «Сегодня, 31 октября, пятница» / «Вчера, …» / «29 октября, среда». */
function feedLabel(day: Day, today: Day): string {
  const { d, month, weekday } = dayParts(day);
  const prefix = day === today ? "Сегодня, " : day === shiftDay(today, -1) ? "Вчера, " : "";
  return `${prefix}${d} ${month}, ${weekday}`;
}

/** «Сегодня, 13:30» / «Вчера, 19:00» / «22 октября, 12:00». */
function whenLabel(meal: Meal, today: Day, tz: string): string {
  const time = clock(meal.at, tz);
  if (meal.day === today) return `Сегодня, ${time}`;
  if (meal.day === shiftDay(today, -1)) return `Вчера, ${time}`;
  const { d, month } = dayParts(meal.day);
  return `${d} ${month}, ${time}`;
}

const kcalText = (value: number | null): string => (value === null ? EMPTY_CELL : String(roundHalfUp(value)));
const gramsText = (value: number | null): string => (value === null ? EMPTY_CELL : `${roundHalfUp(value)} г`);

// ---------------------------------------------------------------- build

/** The links of the page never keep the dialog open: only a meal card sets `meal`. */
const withoutDialog = (params: NutritionParams): NutritionParams => ({ ...params, meal: null });

/** The lower-case ids that more than one of `meals` carries: the dialog cannot tell those meals apart. */
function repeatedIds(meals: readonly Meal[]): ReadonlySet<string> {
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const meal of meals) {
    const id = meal.id.toLowerCase();
    if (seen.has(id)) repeated.add(id);
    seen.add(id);
  }
  return repeated;
}

function card(meal: Meal, key: string, repeated: ReadonlySet<string>, params: NutritionParams, tz: string): MealCard {
  const id = meal.id.toLowerCase();
  return {
    key,
    nickname: meal.nickname,
    name: meal.name,
    kcal: kcalText(meal.kcal),
    time: clock(meal.at, tz),
    photo: meal.photo,
    href: parseMealId(meal.id) === null || repeated.has(id) ? null : nutritionHref(params, { meal: id }),
  };
}

/** `playerId:mealId`, with a counter when even that repeats (one player writing the same id twice). */
function uniqueKeys(meals: readonly Meal[]): Map<Meal, string> {
  const used = new Map<string, number>();
  const keys = new Map<Meal, string>();
  for (const meal of meals) {
    const base = `${meal.playerId}:${meal.id.toLowerCase()}`;
    const n = used.get(base) ?? 0;
    used.set(base, n + 1);
    keys.set(meal, n === 0 ? base : `${base}:${n}`);
  }
  return keys;
}

function groupsOf(
  meals: readonly Meal[],
  repeated: ReadonlySet<string>,
  today: Day,
  params: NutritionParams,
  tz: string,
): DayGroup[] {
  const keys = uniqueKeys(meals);
  const days = [...new Set(meals.map((m) => m.day))].sort().reverse();
  return days.map((day) => {
    const ofDay = meals.filter((m) => m.day === day).sort((a, b) => b.at.getTime() - a.at.getTime());
    return {
      label: feedLabel(day, today),
      sub: records(ofDay.length),
      meals: ofDay.map((m) => card(m, keys.get(m) as string, repeated, params, tz)),
    };
  });
}

interface PlayerDay {
  id: string;
  nickname: string;
  goals: Goals;
  water: number | null;
  today: Meal[];
}

function summaryRow(p: PlayerDay, params: NutritionParams): SummaryRow {
  const count = p.today.length;
  const kcalSum = total(p.today.map((m) => m.kcal));
  const selected = params.who === p.id;
  const status =
    count === 0
      ? ({ label: "Нет записей", tone: "bad" } as const)
      : count === 1
        ? ({ label: "Мало записей", tone: "mid" } as const)
        : ({ label: "В норме", tone: "ok" } as const);
  return {
    id: p.id,
    nickname: p.nickname,
    initials: initials(p.nickname),
    kcal: kcal(kcalSum),
    goal: kcal(p.goals.kcal),
    meals: `${count} ${plural(count, "приём", "приёма", "приёмов")} пищи`,
    fill: Math.min(100, roundHalfUp((kcalSum / p.goals.kcal) * 100)),
    warm: count < 2,
    macros: [
      `Б ${total(p.today.map((m) => m.protein))} г`,
      `Ж ${total(p.today.map((m) => m.fat))} г`,
      `У ${total(p.today.map((m) => m.carbs))} г`,
    ],
    water: p.water === null ? `Вода ${NO_DATA}` : `Вода ${fmt1(p.water / 1000)} / ${fmt1(p.goals.waterMl / 1000)} л`,
    status,
    selected,
    href: nutritionHref(params, { who: selected ? ALL_PLAYERS : p.id }),
  };
}

function dialogOf(meal: Meal | undefined, params: NutritionParams, today: Day, tz: string): DishDialog | null {
  if (!meal) return null;
  return {
    nickname: meal.nickname,
    source: meal.source === null ? null : SOURCE_PILL[meal.source],
    name: meal.name,
    when: whenLabel(meal, today, tz),
    photo: meal.photo,
    tiles: [
      { label: "Ккал", value: kcalText(meal.kcal) },
      { label: "Белки", value: gramsText(meal.protein) },
      { label: "Жиры", value: gramsText(meal.fat) },
      { label: "Углев.", value: gramsText(meal.carbs) },
    ],
    closeHref: nutritionHref(params),
  };
}

/** Runs `fn` over `items` with at most `limit` in flight; results keep the order of `items`. */
async function mapLimit<T, R>(items: readonly T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) {
      const index = next;
      next += 1;
      results[index] = await fn(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

/** Everything the section needs. Player data goes through `lib/staff/access.ts` only. */
export async function loadNutrition(
  deps: Deps,
  ctx: StaffContext,
  query: SearchParams,
  tz: string = STAFF_TIME_ZONE,
): Promise<NutritionData> {
  const roster = await rosterMembers(deps, ctx);
  const players = roster.slice(0, MAX_PLAYERS).map((p) => ({ id: p.id, nickname: p.nickname }));
  const params = parseNutritionParams(query, players.map((p) => p.id));
  const links = withoutDialog(params);
  const today = dayOf(deps.now(), tz);
  const from = shiftDay(today, -(params.days - 1));

  const perPlayer = await mapLimit(players, READ_CONCURRENCY, async (player) => {
    const docs = await playerDocuments(deps, ctx, player.id, ["nutrition"]);
    const meals = mealsOf(docs.nutrition, player, today, tz);
    const day: PlayerDay = {
      ...player,
      goals: goalsOf(docs.nutrition),
      water: waterMl(docs.nutrition, today),
      today: meals.filter((m) => m.day === today),
    };
    return { meals: meals.filter((m) => m.day >= from), day };
  });

  const inRange = perPlayer.flatMap((p) => p.meals);
  const repeated = repeatedIds(inRange);
  const shown = inRange.filter((m) => (params.who === ALL_PLAYERS || m.playerId === params.who) && (!params.photos || m.photo !== null));
  const withPhoto = shown.filter((m) => m.photo !== null).length;
  const nickname = players.find((p) => p.id === params.who)?.nickname;
  const { d, month } = dayParts(today);

  return {
    params,
    players,
    hidden: roster.length - players.length,
    countText: `${records(shown.length)} · ${withPhoto} с фото${nickname === undefined ? "" : ` · ${nickname}`}`,
    groups: groupsOf(shown, repeated, today, links, tz),
    summarySub: `Сегодня, ${d} ${month}. Нажмите на игрока, чтобы показать только его записи`,
    summary: perPlayer.map((p) => summaryRow(p.day, links)),
    // An id carried by several visible meals opens nothing: it would show somebody else's dish.
    dialog: dialogOf(
      params.meal === null || repeated.has(params.meal) ? undefined : inRange.find((m) => m.id.toLowerCase() === params.meal),
      links,
      today,
      tz,
    ),
  };
}
