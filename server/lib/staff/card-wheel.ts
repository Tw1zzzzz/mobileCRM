import { daysEndingAt, isRecord, parseInstant, phoneDayOf, records, type Day, type Documents } from "@/lib/staff/metrics";
import { fmt1, weekdayShort } from "@/lib/staff/overview-format";
import { wheelValues } from "@/lib/staff/series";

/**
 * «Колесо баланса» of the player card: the seven day chips and the radar of the chosen day. Geometry and ring
 * maths are the board's (`Players.dc.html`: 340x340, centre 170, radius 115, spokes from straight up every 45
 * degrees, rings at 2.5 / 5 / 7.5 / 10 of 10); the names are the app's `BalanceCategory`, not the board's.
 */

const SIZE = 340;
const CENTRE = 170;
const RADIUS = 115;
const SCALE_MAX = 10;
const RINGS = [2.5, 5, 7.5, 10] as const;
/** Labels sit a little outside the outer ring: 30 px sideways, 22 px up and down. */
const LABEL_OFFSET_X = 30;
const LABEL_OFFSET_Y = 22;
export const WHEEL_DAYS = 7;

/** `BalanceCategory.title` and `.shortTitle` (core Wellbeing.kt), in the order of `WHEEL_KEYS`. */
const CATEGORIES: readonly { title: string; short: string }[] = [
  { title: "Физическое здоровье", short: "Здоровье" },
  { title: "Эмоциональное состояние", short: "Эмоции" },
  { title: "Интеллектуальное развитие", short: "Интеллект" },
  { title: "Духовное развитие", short: "Духовность" },
  { title: "Профессиональная сфера", short: "Профессия" },
  { title: "Социальные отношения", short: "Отношения" },
  { title: "Окружающая среда", short: "Среда" },
  { title: "Финансовое благополучие", short: "Финансы" },
];

export interface WheelChip {
  label: string;
  href: string;
  on: boolean;
}

export interface WheelLabel {
  text: string;
  /** CSS percentages of the 340 px square. */
  left: string;
  top: string;
}

export interface WheelFigure {
  /** `points` of the four rings, inner first. */
  rings: string[];
  /** `d` of the eight spokes. */
  spokes: string;
  /** `points` of the player's polygon. */
  polygon: string;
  aria: string;
  labels: WheelLabel[];
  list: { name: string; value: string }[];
  /** One per value, in the order of `list`: what pointing at that part of the radar picks and marks. */
  sectors: WheelSector[];
}

export interface WheelSector {
  /** `points` of the wedge around the spoke, out to the outer ring. */
  points: string;
  /** The value's vertex on the radar. */
  x: number;
  y: number;
  /** The same vertex in CSS percentages of the square. */
  left: string;
  top: string;
}

export interface WheelData {
  /** Today first, then the six days before it. */
  chips: WheelChip[];
  /** The day the radar shows: the URL's `day` when it is one of the chips, else today. */
  selected: Day;
  /** Null when the player filled nothing in that day. */
  figure: WheelFigure | null;
}

function angle(index: number): number {
  return -Math.PI / 2 + (index * Math.PI) / 4;
}

function point(index: number, value: number): [number, number] {
  const a = angle(index);
  const r = (RADIUS * value) / SCALE_MAX;
  return [CENTRE + Math.cos(a) * r, CENTRE + Math.sin(a) * r];
}

/** The wedge of spoke `index`: half the way to each neighbouring spoke. */
function sector(index: number, value: number): WheelSector {
  const edge = (delta: number): string => {
    const a = angle(index) + delta;
    const reach = RADIUS / Math.cos(Math.PI / 8);
    return `${(CENTRE + Math.cos(a) * reach).toFixed(1)},${(CENTRE + Math.sin(a) * reach).toFixed(1)}`;
  };
  const [x, y] = point(index, value);
  return {
    points: `${CENTRE},${CENTRE} ${edge(-Math.PI / 8)} ${edge(Math.PI / 8)}`,
    x: Number(x.toFixed(1)),
    y: Number(y.toFixed(1)),
    left: `${((x / SIZE) * 100).toFixed(1)}%`,
    top: `${((y / SIZE) * 100).toFixed(1)}%`,
  };
}

function polygonPoints(values: readonly number[]): string {
  return values
    .map((v, i) => point(i, v))
    .map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`)
    .join(" ");
}

/** The value as a person writes it: «8», «7,5». */
function valueText(value: number): string {
  return Number.isInteger(value) ? String(value) : fmt1(value);
}

/** The balance entry of each phone day; of several entries on a day the last one in the document wins. */
export function balanceByDay(docs: Documents): Map<Day, number[]> {
  const byDay = new Map<Day, number[]>();
  for (const entry of records(docs.wellbeing, "balance")) {
    const at = parseInstant(entry.day);
    if (!at || !isRecord(entry.values)) continue;
    byDay.set(phoneDayOf(at), wheelValues(entry.values));
  }
  return byDay;
}

function chipLabel(day: Day, today: Day, filled: boolean): string {
  const name = day === today ? "Сегодня" : `${weekdayShort(day)} ${Number(day.slice(8, 10))}`;
  return filled ? name : `${name} · —`;
}

function figure(values: readonly number[]): WheelFigure {
  const spokeEnds = values.map((_, i) => point(i, SCALE_MAX));
  return {
    rings: RINGS.map((ring) => polygonPoints(values.map(() => ring))),
    spokes: spokeEnds.map(([x, y]) => `M${CENTRE} ${CENTRE} L${x.toFixed(1)} ${y.toFixed(1)}`).join(" "),
    polygon: polygonPoints(values),
    aria: `Колесо баланса: ${CATEGORIES.map((c, i) => `${c.short} ${valueText(values[i])}`).join(", ")}`,
    labels: CATEGORIES.map((c, i) => {
      const a = angle(i);
      return {
        text: c.short,
        left: `${(((CENTRE + Math.cos(a) * (RADIUS + LABEL_OFFSET_X)) / SIZE) * 100).toFixed(1)}%`,
        top: `${(((CENTRE + Math.sin(a) * (RADIUS + LABEL_OFFSET_Y)) / SIZE) * 100).toFixed(1)}%`,
      };
    }),
    list: CATEGORIES.map((c, i) => ({ name: c.title, value: valueText(values[i]) })),
    sectors: values.map((value, i) => sector(i, value)),
  };
}

/**
 * The tab for `today` (the staff zone's day) and the URL's `day`. `href(day)` builds the chip links, `null`
 * meaning «today» (the default, left out of the URL).
 */
export function wheelData(
  docs: Documents,
  today: Day,
  requested: Day | null,
  href: (day: Day | null) => string,
): WheelData {
  const byDay = balanceByDay(docs);
  const days = daysEndingAt(today, WHEEL_DAYS).reverse();
  const selected = requested !== null && days.includes(requested) ? requested : today;
  return {
    chips: days.map((day) => ({
      label: chipLabel(day, today, byDay.has(day)),
      href: href(day === today ? null : day),
      on: day === selected,
    })),
    selected,
    figure: byDay.has(selected) ? figure(byDay.get(selected) as number[]) : null,
  };
}
