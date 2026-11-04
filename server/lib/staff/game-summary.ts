import type { Deps } from "@/lib/deps";
import type { StaffContext } from "@/lib/staff/access";
import { GAME_KINDS, listGames, type GameKind, type GameSummary } from "@/lib/staff/games";
import { shiftDay, type Day } from "@/lib/staff/metrics";
import { TEAM, type SearchParams } from "@/lib/staff/overview";
import { periodDays, type Period } from "@/lib/staff/series";

/**
 * «Сводка за период» on the «Игровая статистика» page: which period, source and player the URL asks for
 * (`?period=7|30|90|custom&from=&to=&kind=pracc|tournament&who=<player id>`; whatever is left out is the default),
 * and the team's games that match. The numbers are `game-summary-table.ts`.
 */

export const GAME_STATS_PATH = "/staff/game-stats";
/** Where the page is scrolled to after a filter link, so choosing a filter does not jump back to the form. */
export const SUMMARY_ANCHOR = "gs-period";
export const DELETE_ANCHOR = "confirm-delete";

export const SUMMARY_PRESETS = [7, 30, 90] as const;
export type SummaryPeriod = (typeof SUMMARY_PRESETS)[number] | "custom";
const DEFAULT_PERIOD = 30;
const MAX_CUSTOM_DAYS = 366;

export type KindFilter = "all" | GameKind;

export interface SummaryParams {
  period: SummaryPeriod;
  /** The days shown, both inclusive; for a bad custom range, the last 30 days. */
  range: Pick<Period, "from" | "to">;
  kind: KindFilter;
  /** `"team"` or the id of a player of the roster. */
  who: string;
}

const firstOf = (value: string | string[] | undefined): string | undefined => (Array.isArray(value) ? value[0] : value);

/** A real calendar day written `YYYY-MM-DD`, else null. */
function validDay(raw: string | undefined): Day | null {
  if (raw === undefined || !/^\d{4}-\d{2}-\d{2}$/.test(raw)) return null;
  return shiftDay(raw, 0) === raw ? raw : null;
}

const inclusiveDays = (from: Day, to: Day): number =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;

/** The `days` last days ending at `today`. */
function lastDays(today: Day, days: number): Pick<Period, "from" | "to"> {
  const { from, to } = periodDays(today, days);
  return { from, to };
}

function parseRange(raw: SearchParams, today: Day): Pick<SummaryParams, "period" | "range"> {
  const asked = firstOf(raw.period);
  const preset = SUMMARY_PRESETS.find((days) => String(days) === asked);
  if (preset !== undefined) return { period: preset, range: lastDays(today, preset) };
  if (asked === "custom") {
    const from = validDay(firstOf(raw.from));
    const to = validDay(firstOf(raw.to));
    if (from !== null && to !== null && from <= to && inclusiveDays(from, to) <= MAX_CUSTOM_DAYS) {
      return { period: "custom", range: { from, to } };
    }
    return { period: "custom", range: lastDays(today, DEFAULT_PERIOD) };
  }
  return { period: DEFAULT_PERIOD, range: lastDays(today, DEFAULT_PERIOD) };
}

/**
 * The filters from the URL. A `who` that is not on the roster and a `kind` that is not a game kind are «everyone» /
 * «all»: no error, and nothing about the id reaches the data. A bad custom range is the last 30 days.
 */
export function parseSummaryParams(raw: SearchParams, rosterIds: readonly string[], today: Day): SummaryParams {
  const who = firstOf(raw.who);
  const kind = firstOf(raw.kind);
  return {
    ...parseRange(raw, today),
    kind: GAME_KINDS.find((k) => k === kind) ?? "all",
    who: who !== undefined && rosterIds.includes(who) ? who : TEAM,
  };
}

/** The query of the filters; defaults are left out so the plain page keeps a plain URL. */
function summaryQuery(params: SummaryParams): string[] {
  const query: string[] = [];
  if (params.period === "custom") query.push("period=custom", `from=${params.range.from}`, `to=${params.range.to}`);
  else if (params.period !== DEFAULT_PERIOD) query.push(`period=${params.period}`);
  if (params.kind !== "all") query.push(`kind=${params.kind}`);
  if (params.who !== TEAM) query.push(`who=${encodeURIComponent(params.who)}`);
  return query;
}

/** The page's URL with `patch` applied to the filters (and optionally a game to confirm the deletion of). */
export function summaryHref(params: SummaryParams, patch: Partial<SummaryParams> = {}, confirmDelete?: string): string {
  const query = summaryQuery({ ...params, ...patch });
  if (confirmDelete !== undefined) query.push(`delete=${encodeURIComponent(confirmDelete)}`);
  const anchor = confirmDelete === undefined ? SUMMARY_ANCHOR : DELETE_ANCHOR;
  return `${GAME_STATS_PATH}${query.length === 0 ? "" : `?${query.join("&")}`}#${anchor}`;
}

/** The team's games that match the filters, newest first, each with its totals (of the player, if one is chosen). */
export function loadSummaryGames(deps: Deps, ctx: StaffContext, params: SummaryParams): Promise<GameSummary[]> {
  return listGames(deps, ctx, {
    from: params.range.from,
    to: params.range.to,
    kind: params.kind === "all" ? undefined : params.kind,
    playerId: params.who === TEAM ? undefined : params.who,
  });
}
