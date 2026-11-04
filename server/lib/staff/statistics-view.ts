/**
 * Everything the «Статистика» page shows, as ready-to-render text, from the players' per-day series.
 *
 * Pure functions over `PlayerData` (already loaded). Numbers come from `correlatePooled` / `keyLinks` / `medianSplit`
 * (`lib/staff/statistics.ts`), `linesSummary` (`lib/staff/game-lines.ts`); this file formats them the board's way
 * (`Statistics.dc.html`). Team mode pools the player-day pairs of every player; a value without data is «нет данных»,
 * a pair without enough days is «мало данных», never a zero.
 */
import { MATCH_LIMIT } from "@/lib/faceit/client";
import type { FaceitResult } from "@/lib/faceit/cache";
import { FACEIT_MESSAGES, cellColour, faceitInfo, fixed, signedR, type FaceitNoticeData } from "@/lib/staff/card-game";
import { distinctLines, faceitLines, linesSummary, type GameLine } from "@/lib/staff/game-lines";
import { mean, type Day } from "@/lib/staff/metrics";
import { NO_DATA, plural, whole } from "@/lib/staff/overview-format";
import { MONTHS } from "@/lib/staff/players";
import type { DaySeries, Period, PlayerSeries } from "@/lib/staff/series";
import { sparkline } from "@/lib/staff/statistics-charts";
import {
  STAT_METRICS,
  correlatePooled,
  keyLinks,
  medianSplit,
  pairByDay,
  strength,
  type CorrelationMatrix,
  type StatMetric,
} from "@/lib/staff/statistics";

export type MetricId = StatMetric["id"];

/** One player's inputs: the state series, the per-day match means and the Faceit answer. */
export interface PlayerData {
  id: string;
  nickname: string;
  series: PlayerSeries;
  /** Per-day K/D, ADR and win rate of the Faceit matches and the hand-entered maps; null when no game metric was asked. */
  game: { kd: DaySeries; adr: DaySeries; winRate: DaySeries } | null;
  /** The lines `game` is made of (Faceit matches and the player's maps of hand-entered games) in the period. */
  lines?: readonly GameLine[];
  /** Null when Faceit was not asked. */
  faceit: FaceitResult | null;
}

export interface LinkView {
  title: string;
  r: string;
  tone: "up" | "down";
  /** CSS `left` and `width` of the bar's fill. */
  left: string;
  width: string;
  aria: string;
  strength: string;
  /** The «Что это значит» sentence; null when the values are too alike to split. */
  example: string | null;
}

export interface MultipleView {
  id: MetricId;
  label: string;
  group: "state" | "game";
  hasData: boolean;
  avg: string;
  min: string;
  max: string;
  from: string;
  to: string;
  line: string;
  dots: string;
  aria: string;
}

export interface MatrixCell {
  text: string;
  /** `rgba(...)`; null for the diagonal and for «мало данных». */
  background: string | null;
  kind: "r" | "diagonal" | "few";
}

export interface MatrixView {
  head: string[];
  rows: { name: string; cells: MatrixCell[] }[];
}

export interface GameCardView {
  tiles: { label: string; value: string }[];
  /** Team mode: how the numbers are put together. */
  note: string | null;
}

export interface StatsView {
  /** «27 сен – 26 окт 2026 · 30 дней». */
  rangeLabel: string;
  /** Some selected metric has at least one day with data. */
  hasAnyData: boolean;
  links: LinkView[];
  /** Why there is no link card; null when there are cards. */
  linksMessage: string | null;
  multiples: MultipleView[];
  matrix: MatrixView | null;
  /** Null when Faceit was not asked (no game metric selected). */
  game: GameCardView | null;
  notices: FaceitNoticeData[];
  coverage: string | null;
  /** Several players' days are pooled into one sample (team mode, at least two players with data). */
  pooled: boolean;
  /** More than one player: the small multiples show the mean over players per day. */
  team: boolean;
  /** Team mode with more players than the page takes. */
  truncated: { shown: number; total: number } | null;
}

export const TOO_FEW = "мало данных";
const SELECT_MORE = "Выберите хотя бы две метрики — лучше одну из состояния и одну из игры.";
const NOT_ENOUGH_FOR_LINKS = "Связей пока не найти: у пары метрик должно быть хотя бы 3 общих дня с данными.";

const METRICS = new Map<MetricId, StatMetric>(STAT_METRICS.map((m) => [m.id, m]));
const DECIMALS: Record<MetricId, number> = {
  mood: 1, energy: 1, sleep: 1, screen: 1, cog: 0, balance: 1, kcal: 0, elo: 0, kd: 2, adr: 1, wr: 0,
};

function metric(id: MetricId): StatMetric {
  return METRICS.get(id) as StatMetric;
}

/** The board's `fmt`: «7.5 ч», «2095», «1.07», «54%», «2100 ккал». */
export function formatMetric(id: MetricId, value: number): string {
  const text = fixed(value, DECIMALS[id]);
  const { unit } = metric(id);
  return unit === "" ? text : unit === "%" ? `${text}%` : `${text} ${unit}`;
}

function seriesOf(id: MetricId, player: PlayerData): DaySeries {
  switch (id) {
    case "kd": return player.game?.kd ?? {};
    case "adr": return player.game?.adr ?? {};
    case "wr": return player.game?.winRate ?? {};
    default: return player.series[id];
  }
}

const hasDays = (series: DaySeries): boolean => Object.keys(series).length > 0;

/** Player-day pairs of two metrics, every player's paired days in one sample. */
function pooled(a: MetricId, b: MetricId, players: readonly PlayerData[]): { xs: number[]; ys: number[] } {
  const xs: number[] = [];
  const ys: number[] = [];
  for (const player of players) {
    const paired = pairByDay(seriesOf(a, player), seriesOf(b, player));
    for (let i = 0; i < paired.xs.length; i++) {
      xs.push(paired.xs[i]);
      ys.push(paired.ys[i]);
    }
  }
  return { xs, ys };
}

function dayLabel(day: Day): string {
  const [, month, date] = day.split("-").map(Number);
  return `${date} ${MONTHS[month - 1]}`;
}

export function rangeLabel(period: Period): string {
  const count = period.days.length;
  return `${dayLabel(period.from)} – ${dayLabel(period.to)} ${period.to.slice(0, 4)} · ${count} ${plural(count, "день", "дня", "дней")}`;
}

// ---------------------------------------------------------------- links, matrix

function linkView(a: MetricId, b: MetricId, r: number, players: readonly PlayerData[]): LinkView {
  const size = Math.abs(r);
  const { label, direction } = strength(r);
  const suffix = direction === "direct" ? " прямая связь" : direction === "inverse" ? " обратная связь" : "";
  const { xs, ys } = pooled(a, b, players);
  const split = medianSplit(xs, ys);
  const example =
    split === null
      ? null
      : `Когда ${metric(a).lower} выше обычного (от ${formatMetric(a, split.median)}), ${metric(b).lower} в среднем ` +
        `${formatMetric(b, split.hiMean)}; когда ниже — ${formatMetric(b, split.loMean)}.`;
  return {
    title: `${metric(a).label} ↔ ${metric(b).label}`,
    r: signedR(r),
    tone: r >= 0 ? "up" : "down",
    left: `${(r >= 0 ? 50 : 50 - size * 50).toFixed(1)}%`,
    width: `${(size * 50).toFixed(1)}%`,
    aria: `Коэффициент корреляции ${signedR(r)}`,
    strength: label + suffix,
    example,
  };
}

function matrixView(ids: readonly MetricId[], r: (a: MetricId, b: MetricId) => number | null): MatrixView {
  return {
    head: ids.map((id) => metric(id).label),
    rows: ids.map((a) => ({
      name: metric(a).label,
      cells: ids.map((b): MatrixCell => {
        if (a === b) return { text: "—", background: null, kind: "diagonal" };
        const value = r(a, b);
        return value === null
          ? { text: TOO_FEW, background: null, kind: "few" }
          : { text: signedR(value), background: cellColour(value), kind: "r" };
      }),
    })),
  };
}

// ---------------------------------------------------------------- small multiples

/** The player's own series, or the mean over the players that have a value that day (team mode). */
function dailyValues(id: MetricId, players: readonly PlayerData[], period: Period): (number | null)[] {
  return period.days.map((day) => {
    const values = players.map((p) => seriesOf(id, p)[day]).filter((v): v is number => typeof v === "number");
    return mean(values);
  });
}

function multipleView(id: MetricId, players: readonly PlayerData[], period: Period): MultipleView {
  const { label, group } = metric(id);
  const values = dailyValues(id, players, period);
  const present = values.filter((v): v is number => v !== null);
  const base = { id, label, group, from: dayLabel(period.from), to: dayLabel(period.to) };
  if (present.length === 0) {
    return { ...base, hasData: false, avg: NO_DATA, min: NO_DATA, max: NO_DATA, line: "", dots: "", aria: `${label}: ${NO_DATA}` };
  }
  const avg = formatMetric(id, mean(present) as number);
  return {
    ...base,
    hasData: true,
    avg,
    min: formatMetric(id, Math.min(...present)),
    max: formatMetric(id, Math.max(...present)),
    ...sparkline(values),
    aria: `${label}: среднее ${avg}`,
  };
}

// ---------------------------------------------------------------- Faceit

function lastValue(series: DaySeries): number | null {
  const days = Object.keys(series).sort();
  return days.length === 0 ? null : series[days[days.length - 1]];
}

function gameCard(players: readonly PlayerData[], period: Period, tz: string): GameCardView {
  const answered = players.filter((p) => p.faceit?.status === "ok");
  const lines = players.flatMap(
    (p) => p.lines ?? faceitLines(p.faceit?.status === "ok" ? p.faceit.matches : [], period.from, period.to, tz),
  );
  // Maps played and Win Rate count a match / map once however many selected players were in it; K/D and ADR
  // average every selected player's own lines.
  const summary = linesSummary(lines);
  const distinct = linesSummary(distinctLines(lines));
  const ended = players.map((p) => lastValue(p.series.elo)).filter((v): v is number => v !== null);
  const elo = mean(ended);
  const some = answered.length > 0 || lines.length > 0;
  const orNo = (value: number | null, format: (v: number) => string): string => (value === null ? NO_DATA : format(value));
  return {
    tiles: [
      { label: "Сыграно карт", value: some ? String(distinct.matches) : NO_DATA },
      { label: "Средний K/D", value: orNo(summary.avgKD, (v) => fixed(v, 2)) },
      { label: "Средний ADR", value: orNo(summary.adr, (v) => fixed(v, 1)) },
      { label: "Win Rate", value: orNo(distinct.winRate, (v) => `${whole(v)}%`) },
      { label: "ELO на конец периода", value: orNo(elo, (v) => fixed(v, 0)) },
    ],
    note:
      players.length > 1
        ? "Карты и Win Rate — по матчам Faceit и играм команды (пракки, турниры), общий матч или карта считается один раз; K/D и ADR — по матчам и картам каждого игрока; ELO — среднее значение на конец периода по игрокам, у которых он есть."
        : null,
  };
}

const MATCH_WORD = (k: number, n: number): string => `у ${k} из ${n} ${plural(n, "игрока", "игроков", "игроков")}`;

function faceitNotices(
  players: readonly PlayerData[],
  period: Period,
  today: Day,
  tz: string,
): { notices: FaceitNoticeData[]; coverage: string | null } {
  const results = players.map((p) => p.faceit);
  if (players.length === 0 || results.some((r) => r === null)) return { notices: [], coverage: null };
  const asked = results as FaceitResult[];
  if (asked.length === 1) {
    const info = faceitInfo(asked[0], period, today, tz);
    return { notices: info.notice ? [info.notice] : [], coverage: info.coverage };
  }

  const notices: FaceitNoticeData[] = [];
  const missing = asked.filter((r) => r.status !== "ok");
  if (missing.length > 0) {
    const first = missing[0].status as keyof typeof FACEIT_MESSAGES;
    const same = missing.length === asked.length && missing.every((r) => r.status === first);
    notices.push({
      message: same ? FACEIT_MESSAGES[first] : `Данных Faceit нет ${MATCH_WORD(missing.length, asked.length)}`,
      asOf: null,
    });
  }
  const stale = asked.filter((r) => r.status === "ok" && r.stale).length;
  if (stale > 0) {
    notices.push({
      message: `Faceit временно недоступен — ${MATCH_WORD(stale, asked.length)} показаны ранее сохранённые данные`,
      asOf: null,
    });
  }
  const capped = asked.filter((r) => faceitInfo(r, period, today, tz).coverage !== null).length;
  const coverage =
    capped > 0
      ? `Матчи Faceit покрыты не с начала периода ${MATCH_WORD(capped, asked.length)}: Faceit отдаёт последние ${MATCH_LIMIT} матчей`
      : null;
  return { notices, coverage };
}

// ---------------------------------------------------------------- the page

export interface ViewInput {
  metrics: readonly MetricId[];
  period: Period;
  players: readonly PlayerData[];
  /** How many players the team has; more than `players` when the page took the first few. */
  totalPlayers: number;
  today: Day;
  tz: string;
}

export function buildView(input: ViewInput): StatsView {
  const { metrics, period, players, today, tz } = input;
  const rOf = (a: MetricId, b: MetricId): number | null =>
    correlatePooled(players.map((p) => ({ a: seriesOf(a, p), b: seriesOf(b, p) })));

  const matrix: CorrelationMatrix = {};
  for (let i = 0; i < metrics.length; i++) {
    for (let j = i + 1; j < metrics.length; j++) {
      matrix[metrics[i]] = { ...matrix[metrics[i]], [metrics[j]]: rOf(metrics[i], metrics[j]) };
    }
  }
  const links = keyLinks(
    metrics.map((id) => ({ id, group: metric(id).group })),
    matrix,
  ).map((link) => linkView(link.a as MetricId, link.b as MetricId, link.r, players));

  const faceitAsked = players.some((p) => p.faceit !== null);
  const { notices, coverage } = faceitNotices(players, period, today, tz);
  return {
    rangeLabel: rangeLabel(period),
    hasAnyData: metrics.some((id) => players.some((p) => hasDays(seriesOf(id, p)))),
    pooled: players.length > 1 && players.filter((p) => metrics.some((id) => hasDays(seriesOf(id, p)))).length >= 2,
    team: players.length > 1,
    links,
    linksMessage: links.length > 0 ? null : metrics.length < 2 ? SELECT_MORE : NOT_ENOUGH_FOR_LINKS,
    multiples: metrics.map((id) => multipleView(id, players, period)),
    matrix:
      metrics.length < 2
        ? null
        : matrixView(metrics, (a, b) => {
            const pair = matrix[a]?.[b] ?? matrix[b]?.[a];
            return pair ?? null;
          }),
    game: faceitAsked ? gameCard(players, period, tz) : null,
    notices,
    coverage,
    truncated: input.totalPlayers > players.length ? { shown: players.length, total: input.totalPlayers } : null,
  };
}
