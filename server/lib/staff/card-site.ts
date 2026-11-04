/**
 * The hand-entered games (pracc / tournament) of one player for the card's «Игра» and «Корреляции» tabs: the lines the
 * tiles are computed from and the list of the player's games. Reads go through `listGames` (the caller's team only).
 */
import type { Deps } from "@/lib/deps";
import type { StaffContext } from "@/lib/staff/access";
import { monthDay } from "@/lib/staff/card-format";
import { siteLines, type GameLine } from "@/lib/staff/game-lines";
import { listGames, type GameKind, type GameSummary } from "@/lib/staff/games";
import type { Day } from "@/lib/staff/metrics";
import { NO_DATA } from "@/lib/staff/overview-format";
import type { Period } from "@/lib/staff/series";

/** The list under the tiles shows this many games at most. */
export const GAME_ROWS = 20;

export interface SiteGameRow {
  date: string;
  /** «Пракк» / «Турнир». */
  source: string;
  /** «—» when no opponent was entered. */
  opponent: string;
  /** «Mirage 13:7, Inferno 9:13» — the maps the player played. */
  maps: string;
  /** The player's K/D over those maps. */
  kd: string;
}

export interface SiteGames {
  lines: GameLine[];
  /** Newest first, at most `GAME_ROWS`. */
  rows: SiteGameRow[];
  /** More games than `rows`. */
  total: number;
}

export const NO_SITE_GAMES: SiteGames = { lines: [], rows: [], total: 0 };

const SOURCE_NAMES: Record<GameKind, string> = { pracc: "Пракк", tournament: "Турнир" };

const mapName = (map: string): string => map.charAt(0).toUpperCase() + map.slice(1);

function rowOf(game: GameSummary, playerId: string, today: Day): SiteGameRow {
  const played = game.maps.filter((m) => m.rows.some((r) => r.playerId === playerId));
  return {
    date: monthDay(game.playedOn, today),
    source: SOURCE_NAMES[game.kind],
    opponent: game.opponent ?? "—",
    maps: played.map((m) => `${mapName(m.map)} ${m.ourScore}:${m.theirScore}`).join(", "),
    kd: game.totals.kd === null ? NO_DATA : game.totals.kd.toFixed(2),
  };
}

/** The player's games of `kind` (both kinds when omitted) in the period. */
export async function loadSiteGames(
  deps: Deps,
  ctx: StaffContext,
  playerId: string,
  period: Pick<Period, "from" | "to">,
  today: Day,
  kind?: GameKind,
): Promise<SiteGames> {
  const games = await listGames(deps, ctx, { from: period.from, to: period.to, kind, playerId });
  return {
    lines: games.flatMap((game) => siteLines([game], playerId)),
    rows: games.slice(0, GAME_ROWS).map((game) => rowOf(game, playerId, today)),
    total: games.length,
  };
}
