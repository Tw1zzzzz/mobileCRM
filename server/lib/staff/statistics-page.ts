import type { Deps } from "@/lib/deps";
import { loadMatches, type FaceitResult } from "@/lib/faceit/cache";
import { playerDocuments, teamPlayers, type Collection, type StaffContext, type TeamPlayer } from "@/lib/staff/access";
import { faceitIdentity } from "@/lib/staff/card";
import { matchDaily } from "@/lib/staff/game";
import { STAFF_TIME_ZONE, dayOf, shiftDay, type Day } from "@/lib/staff/metrics";
import { TEAM, type RosterEntry, type SearchParams } from "@/lib/staff/overview";
import { periodDays, playerSeries, type Period } from "@/lib/staff/series";
import { STAT_METRICS } from "@/lib/staff/statistics";
import { buildView, type MetricId, type PlayerData, type StatsView } from "@/lib/staff/statistics-view";
import { playerCodeOf } from "@/lib/teams";

/**
 * «Статистика»: which players, metrics and period the URL asks for (`?who=&m=&period=&from=&to=`), and every number
 * the correlation analysis shows, computed from the roster's synced documents (read through `lib/staff/access.ts`)
 * and, for the game metrics, from Faceit.
 */

export const STATS_PATH = "/staff/statistics";

export const STATS_PRESETS = [7, 30, 90, 365] as const;
export type StatsPeriod = (typeof STATS_PRESETS)[number] | "custom";
const DEFAULT_PERIOD = 30;
const MAX_CUSTOM_DAYS = 366;

export const DEFAULT_METRICS: readonly MetricId[] = ["mood", "sleep", "screen", "elo", "kd", "adr"];
export const MAX_METRICS = 8;
/** Team mode takes the roster's first players only (Faceit is asked once per player). */
export const MAX_STAT_PLAYERS = 12;
/** Faceit requests in flight at once. */
const FACEIT_PARALLEL = 4;

/** The collections the statistics are computed from. */
const STATS_COLLECTIONS = ["sleep", "nutrition", "wellbeing", "cognitive", "screen-time", "faceit"] as const satisfies readonly Collection[];

const KNOWN: ReadonlySet<string> = new Set(STAT_METRICS.map((m) => m.id));
/** The metrics that need Faceit matches; `elo` is read from the players' synced snapshots and never asks Faceit. */
export const NEEDS_FACEIT: ReadonlySet<string> = new Set(["kd", "adr", "wr"]);

export interface StatsParams {
  /** `"team"` or the id of a player of the roster. */
  who: string;
  /** In the order they were picked; at most `MAX_METRICS`. */
  metrics: readonly MetricId[];
  period: StatsPeriod;
  /** The days analysed; for a bad custom range, the last 30 days. */
  range: Period;
}

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** A real calendar day written `YYYY-MM-DD`, else null. */
function validDay(raw: string | undefined): Day | null {
  if (raw === undefined || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return shiftDay(raw, 0) === raw ? raw : null;
}

function inclusiveDays(from: Day, to: Day): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
}

function parseMetrics(raw: string | undefined): readonly MetricId[] {
  if (raw === undefined) return DEFAULT_METRICS;
  if (raw === "") return [];
  const seen = new Set<string>();
  for (const token of raw.split(",").map((t) => t.trim())) if (KNOWN.has(token)) seen.add(token);
  return seen.size === 0 ? DEFAULT_METRICS : ([...seen].slice(0, MAX_METRICS) as MetricId[]);
}

function parseRange(raw: SearchParams, today: Day): { period: StatsPeriod; range: Period } {
  const asked = firstOf(raw.period);
  const preset = STATS_PRESETS.find((p) => String(p) === asked);
  if (preset !== undefined) return { period: preset, range: periodDays(today, preset) };
  if (asked === "custom") {
    const from = validDay(firstOf(raw.from));
    const to = validDay(firstOf(raw.to));
    if (from !== null && to !== null && from <= to && inclusiveDays(from, to) <= MAX_CUSTOM_DAYS) {
      return { period: "custom", range: periodDays(to, inclusiveDays(from, to)) };
    }
    return { period: "custom", range: periodDays(today, DEFAULT_PERIOD) };
  }
  return { period: DEFAULT_PERIOD, range: periodDays(today, DEFAULT_PERIOD) };
}

/**
 * The selection from the URL. A `who` that is not on the roster (another team's player, a solo player, staff,
 * garbage) is the whole team: no error, and nothing about the id is read or echoed. Unknown metrics are dropped,
 * more than eight are cut, a bad custom range is the last 30 days.
 */
export function parseStatsParams(raw: SearchParams, rosterIds: readonly string[], today: Day): StatsParams {
  const who = firstOf(raw.who);
  return {
    who: who !== undefined && rosterIds.includes(who) ? who : TEAM,
    metrics: parseMetrics(firstOf(raw.m)),
    ...parseRange(raw, today),
  };
}

function sameMetrics(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, i) => id === b[i]);
}

/** The URL of the page with `patch` applied; defaults are left out so the plain page keeps a plain URL. */
export function statsHref(params: StatsParams, patch: Partial<StatsParams> = {}): string {
  const next = { ...params, ...patch };
  const query: string[] = [];
  if (next.who !== TEAM) query.push(`who=${encodeURIComponent(next.who)}`);
  if (!sameMetrics(next.metrics, DEFAULT_METRICS)) query.push(`m=${next.metrics.join(",")}`);
  if (next.period === "custom") {
    query.push("period=custom", `from=${next.range.from}`, `to=${next.range.to}`);
  } else if (next.period !== DEFAULT_PERIOD) {
    query.push(`period=${next.period}`);
  }
  return query.length === 0 ? STATS_PATH : `${STATS_PATH}?${query.join("&")}`;
}

/** The metric picker's link: `id` switched off when it is on, else added while there is room. */
export function toggleMetric(params: StatsParams, id: MetricId): StatsParams {
  const metrics = params.metrics.includes(id)
    ? params.metrics.filter((m) => m !== id)
    : params.metrics.length < MAX_METRICS
      ? [...params.metrics, id]
      : params.metrics;
  return { ...params, metrics };
}

// ---------------------------------------------------------------- data

export type StatsPage =
  /** No player has joined yet: the page shows the code to send them. */
  | { kind: "empty"; playerCode: string }
  | { kind: "data"; params: StatsParams; players: RosterEntry[]; view: StatsView };

/** Test seam: the HTTP client and key used for Faceit (defaults: global `fetch`, `FACEIT_API_KEY`). */
export interface StatsOptions {
  http?: typeof fetch;
  apiKey?: string;
}

/** Runs `work` over `items` with at most `limit` calls in flight; results keep the order of `items`. */
async function mapLimited<T, R>(items: readonly T[], limit: number, work: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  const worker = async (): Promise<void> => {
    while (next < items.length) {
      const index = next++;
      results[index] = await work(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

async function faceitOf(
  deps: Deps,
  player: TeamPlayer,
  docs: Partial<Record<Collection, unknown>>,
  options: StatsOptions,
): Promise<FaceitResult> {
  try {
    // `player.id` is a roster-checked id; `loadMatches` trusts its caller.
    return await loadMatches(deps, player.id, faceitIdentity(docs.faceit), options.http ?? fetch, options.apiKey);
  } catch (error: unknown) {
    // A database failure for one player must not take the page down: that player counts as having no Faceit data.
    console.error("Faceit matches unavailable for a statistics player", error instanceof Error ? error.message : error);
    return { status: "unavailable" };
  }
}

/**
 * Everything the page needs. Faceit is asked only when K/D, ADR or Win Rate is selected (`NEEDS_FACEIT`), and only for the roster's
 * players the page shows (never for an id from the URL). `tz` is the staff time zone.
 */
export async function loadStatistics(
  deps: Deps,
  ctx: StaffContext,
  raw: SearchParams,
  tz: string = STAFF_TIME_ZONE,
  options: StatsOptions = {},
): Promise<StatsPage> {
  const roster = await teamPlayers(deps, ctx);
  if (roster.length === 0) return { kind: "empty", playerCode: await playerCodeOf(deps, ctx.teamId) };

  const today = dayOf(deps.now(), tz);
  const params = parseStatsParams(raw, roster.map((p) => p.id), today);
  const scope = params.who === TEAM ? roster.slice(0, MAX_STAT_PLAYERS) : roster.filter((p) => p.id === params.who);
  const { from, to } = params.range;

  const docs = await Promise.all(scope.map((p) => playerDocuments(deps, ctx, p.id, STATS_COLLECTIONS)));
  const wantsFaceit = params.metrics.some((id) => NEEDS_FACEIT.has(id));
  const faceit = wantsFaceit
    ? await mapLimited(scope, FACEIT_PARALLEL, (p) => faceitOf(deps, p, docs[scope.indexOf(p)], options))
    : scope.map(() => null);

  const players: PlayerData[] = scope.map((p, i) => {
    const result = faceit[i];
    return {
      id: p.id,
      nickname: p.nickname,
      series: playerSeries({ id: p.id, docs: docs[i] }, from, to, tz),
      game: result?.status === "ok" ? matchDaily(result.matches, from, to, tz) : null,
      faceit: result,
    };
  });
  return {
    kind: "data",
    params,
    players: roster.map((p) => ({ id: p.id, nickname: p.nickname })),
    view: buildView({ metrics: params.metrics, period: params.range, players, totalPlayers: params.who === TEAM ? roster.length : 1, today, tz }),
  };
}
