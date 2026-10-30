import type { Deps } from "@/lib/deps";
import { playerDocuments, teamPlayers, type Collection, type StaffContext } from "@/lib/staff/access";
import { STAFF_TIME_ZONE, dayOf, teamOverview, type OverviewData } from "@/lib/staff/metrics";
import { playerCodeOf } from "@/lib/teams";

/** The collections «Обзор» is computed from; staff read them only through `lib/staff/access.ts`. */
export const OVERVIEW_COLLECTIONS = ["sleep", "wellbeing", "cognitive", "nutrition"] as const satisfies readonly Collection[];

/** The board's metric chips. «Игра» is added with plan C. */
export type ShowKey = "state" | "sleep" | "food";
export const SHOW_KEYS: readonly ShowKey[] = ["state", "sleep", "food"];
/** The board starts with «Состояние» and «Сон» on and «Питание» off. */
export const DEFAULT_SHOW: readonly ShowKey[] = ["state", "sleep"];

export const TEAM = "team";
export const OVERVIEW_PATH = "/staff/overview";

/** The page's filters: `who` is `"team"` or the id of a player of the roster; `show` is in canonical order. */
export interface OverviewFilters {
  who: string;
  show: readonly ShowKey[];
}

export type SearchParams = Record<string, string | string[] | undefined>;

function firstOf(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/**
 * Filters from the URL. A `who` that is not in the roster (another team's player, garbage) is the whole team:
 * no error, and nothing about the id reaches the data. A missing `show` is the default; `show=` is «nothing
 * selected»; a `show` without any known metric is the default as well.
 */
export function parseFilters(params: SearchParams, rosterIds: readonly string[]): OverviewFilters {
  const who = firstOf(params.who);
  const show = firstOf(params.show);
  return {
    who: who !== undefined && rosterIds.includes(who) ? who : TEAM,
    show: parseShow(show),
  };
}

function parseShow(raw: string | undefined): readonly ShowKey[] {
  if (raw === undefined) return DEFAULT_SHOW;
  if (raw === "") return [];
  const tokens = raw.split(",").map((t) => t.trim());
  const valid = SHOW_KEYS.filter((key) => tokens.includes(key));
  return valid.length === 0 ? DEFAULT_SHOW : valid;
}

function sameShow(a: readonly ShowKey[], b: readonly ShowKey[]): boolean {
  return a.length === b.length && a.every((key, i) => key === b[i]);
}

/** The URL of the page with these filters; defaults are left out so the plain page keeps a plain URL. */
export function overviewHref(filters: OverviewFilters): string {
  const query: string[] = [];
  if (filters.who !== TEAM) query.push(`who=${encodeURIComponent(filters.who)}`);
  if (!sameShow(filters.show, DEFAULT_SHOW)) query.push(`show=${filters.show.join(",")}`);
  return query.length === 0 ? OVERVIEW_PATH : `${OVERVIEW_PATH}?${query.join("&")}`;
}

/** `filters` with one metric chip switched on or off. */
export function toggleShow(filters: OverviewFilters, key: ShowKey): OverviewFilters {
  const on = filters.show.includes(key);
  return { ...filters, show: SHOW_KEYS.filter((k) => (k === key ? !on : filters.show.includes(k))) };
}

export interface RosterEntry {
  id: string;
  nickname: string;
}

export type OverviewPage =
  /** No player has joined yet: the page shows the code to send them. */
  | { kind: "empty"; playerCode: string }
  | { kind: "data"; overview: OverviewData; players: RosterEntry[]; filters: OverviewFilters };

/**
 * Everything the page needs: the roster, the filters taken from the URL, and the numbers. `today` is the
 * current calendar day in the staff time zone, not the server's.
 */
export async function loadOverview(
  deps: Deps,
  ctx: StaffContext,
  params: SearchParams,
  tz: string = STAFF_TIME_ZONE,
): Promise<OverviewPage> {
  const roster = await teamPlayers(deps, ctx);
  if (roster.length === 0) return { kind: "empty", playerCode: await playerCodeOf(deps, ctx.teamId) };

  const filters = parseFilters(params, roster.map((p) => p.id));
  const documents = await Promise.all(
    roster.map(async (p) => ({ id: p.id, docs: await playerDocuments(deps, ctx, p.id, OVERVIEW_COLLECTIONS) })),
  );
  return {
    kind: "data",
    overview: teamOverview(documents, dayOf(deps.now(), tz), tz, filters.who),
    players: roster.map((p) => ({ id: p.id, nickname: p.nickname })),
    filters,
  };
}
