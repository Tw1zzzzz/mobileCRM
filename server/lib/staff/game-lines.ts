/**
 * One player's games from both sources as the same kind of line, so the card's «Игра» / «Корреляции» tabs and the
 * «Статистика» page can put Faceit matches and hand-entered (pracc / tournament) maps together.
 *
 * A Faceit match is one map of one player; a site line is one player's line on one map. Pure functions; a value that
 * cannot be computed is `null` (the pages print «нет данных»), never a made-up zero. Formulas: `games-math.ts`
 * (K/D, map result, rounds) and `game.ts` (the tiles and per-day means the Faceit-only view already uses).
 *
 * In a merged list the optional columns are plain means over the lines that have them (the rule of the game form
 * for several lines), so UDR here is the mean of the lines' per-round values; the Faceit-only tab keeps its own
 * rounds-weighted UDR (`gameSummary`).
 */
import type { FaceitMatch } from "@/lib/faceit/mapper";
import { emptyTiles, type GameTiles } from "@/lib/staff/game";
import { kd as kdOf, mapResult, roundsOf, type MapResult } from "@/lib/staff/games-math";
import type { Game } from "@/lib/staff/games";
import { dayOf, mean, type Day } from "@/lib/staff/metrics";
import type { DaySeries } from "@/lib/staff/series";

export interface GameLine {
  /** A Faceit match id or a site map id: teammates in the same match / map share it. */
  key: string;
  source: "faceit" | "site";
  day: Day;
  kills: number;
  deaths: number;
  assists: number;
  adr: number;
  /** Rounds played; 0 when unknown. */
  rounds: number;
  ourScore: number;
  theirScore: number;
  result: MapResult;
  /** Not entered / not given by the source: null. */
  firstKills: number | null;
  firstDeaths: number | null;
  /** Entry duels won minus lost. */
  openDuelDiff: number | null;
  kast: number | null;
  udr: number | null;
  multikills: number | null;
  clutches: number | null;
  flashTime: number | null;
  /** Of the two pistol rounds; null when the source has no pistol data. */
  pistolsWon: number | null;
}

const orNull = (value: number | null | undefined): number | null => value ?? null;

/** The Faceit matches finished on the days `from..to` (inclusive, in `tz`) as lines. */
export function faceitLines(matches: readonly FaceitMatch[], from: Day, to: Day, tz: string): GameLine[] {
  return matches.flatMap((m): GameLine[] => {
    const day = dayOf(new Date(m.finishedAt), tz);
    if (day < from || day > to) return [];
    const multi = m.multiKills ? m.multiKills.k2 + m.multiKills.k3 + m.multiKills.k4 + m.multiKills.k5 : null;
    return [{
      key: m.id, source: "faceit", day, kills: m.kills, deaths: m.deaths, assists: m.assists, adr: m.adr, rounds: m.rounds,
      ourScore: m.scoreOwn, theirScore: m.scoreOther, result: m.won ? "win" : "loss",
      firstKills: orNull(m.firstKills), firstDeaths: null,
      openDuelDiff: m.entryCount === undefined ? null : 2 * (m.entryWins ?? 0) - m.entryCount,
      kast: null,
      udr: m.utilityDamage === undefined || m.rounds <= 0 ? null : m.utilityDamage / m.rounds,
      multikills: multi, clutches: orNull(m.clutchWins), flashTime: null, pistolsWon: null,
    }];
  });
}

/**
 * The lines of `playerId` in the games, one per map the player played (the lines of stand-ins and teammates are
 * not theirs). Several players of one map share the map's key.
 */
export function siteLines(games: readonly Game[], playerId: string): GameLine[] {
  return games.flatMap((game) =>
    game.maps.flatMap((map): GameLine[] => {
      const row = map.rows.find((r) => r.playerId === playerId);
      if (row === undefined) return [];
      const fk = row.fk;
      const fd = row.fd;
      return [{
        key: map.id, source: "site", day: game.playedOn, kills: row.kills, deaths: row.deaths, assists: row.assists, adr: row.adr,
        rounds: roundsOf(map), ourScore: map.ourScore, theirScore: map.theirScore, result: mapResult(map.ourScore, map.theirScore),
        firstKills: fk, firstDeaths: fd, openDuelDiff: fk === null || fd === null ? null : fk - fd,
        kast: row.kast, udr: row.udr, multikills: row.multikills, clutches: row.clutches, flashTime: row.flashTime,
        pistolsWon: Number(map.pistol1 === "won") + Number(map.pistol2 === "won"),
      }];
    }),
  );
}

/** The first line of each key: a map two selected players both played counts once. */
export function distinctLines(lines: readonly GameLine[]): GameLine[] {
  const seen = new Set<string>();
  return lines.filter((line) => (seen.has(`${line.source}:${line.key}`) ? false : (seen.add(`${line.source}:${line.key}`), true)));
}

const sum = (values: readonly number[]): number => values.reduce((total, value) => total + value, 0);
const entered = (values: readonly (number | null)[]): number[] => values.filter((v): v is number => v !== null);
const total = (values: readonly (number | null)[]): number | null => {
  const list = entered(values);
  return list.length > 0 ? sum(list) : null;
};
const ratio = (part: number, whole: number): number | null => (whole > 0 ? part / whole : null);
const percent = (part: number, whole: number): number | null => ratio(part * 100, whole);

/** Every tile of the «Игра» tab for the lines (already limited to the period). */
export function linesSummary(lines: readonly GameLine[]): GameTiles {
  if (lines.length === 0) return emptyTiles();
  const count = lines.length;
  const kills = sum(lines.map((l) => l.kills));
  const deaths = sum(lines.map((l) => l.deaths));
  const rounds = sum(lines.map((l) => l.rounds));
  const wins = lines.filter((l) => l.result === "win").length;
  const hasSite = lines.some((l) => l.source === "site");
  const scored = lines.filter((l) => l.ourScore + l.theirScore > 0);
  const scoredRounds = sum(scored.map((l) => l.rounds));
  const roundsWon = sum(scored.map((l) => l.ourScore));
  const withPistols = lines.filter((l) => l.pistolsWon !== null);
  const pistolsWon = sum(withPistols.map((l) => l.pistolsWon as number));
  const pistols = withPistols.length * 2;

  return {
    ...emptyTiles(),
    matches: count,
    wins,
    losses: lines.filter((l) => l.result === "loss").length,
    // Faceit matches have no draws: the count is real only when hand-entered maps are among the lines.
    draws: hasSite ? lines.filter((l) => l.result === "draw").length : null,
    winRate: percent(wins, count),
    kills,
    deaths,
    assists: sum(lines.map((l) => l.assists)),
    kd: kdOf(kills, deaths),
    adr: mean(lines.map((l) => l.adr)),
    kpr: ratio(kills, rounds),
    deathsPerRound: ratio(deaths, rounds),
    avgKR: ratio(kills, rounds),
    avgKD: mean(lines.map((l) => l.kills / Math.max(1, l.deaths))),
    kast: mean(entered(lines.map((l) => l.kast))),
    firstKills: total(lines.map((l) => l.firstKills)),
    firstDeaths: total(lines.map((l) => l.firstDeaths)),
    udr: mean(entered(lines.map((l) => l.udr))),
    openDuelDiff: total(lines.map((l) => l.openDuelDiff)),
    avgMultikills: mean(entered(lines.map((l) => l.multikills))),
    clutchesWon: total(lines.map((l) => l.clutches)),
    avgFlashTime: mean(entered(lines.map((l) => l.flashTime))),
    rounds: scored.length > 0 ? scoredRounds : null,
    roundsWon: scored.length > 0 ? roundsWon : null,
    roundWinRate: scored.length > 0 ? percent(roundsWon, scoredRounds) : null,
    avgRoundsWon: scored.length > 0 ? roundsWon / scored.length : null,
    avgRoundsLost: scored.length > 0 ? (scoredRounds - roundsWon) / scored.length : null,
    pistolWon: pistols > 0 ? pistolsWon : null,
    pistolRounds: pistols > 0 ? pistols : null,
    pistolWinRate: percent(pistolsWon, pistols),
  };
}

/**
 * Per-day means over the lines of each day: K/D (kills / max(1, deaths) per line), ADR and win rate (100 for a won
 * map or match, 0 otherwise). A day without lines has no key. The same shapes as `matchDaily`.
 */
export function linesDaily(lines: readonly GameLine[]): { kd: DaySeries; adr: DaySeries; winRate: DaySeries } {
  const byDay = Map.groupBy(lines, (line) => line.day);
  const series = (value: (line: GameLine) => number): DaySeries =>
    Object.fromEntries(
      [...byDay.entries()]
        .sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([day, list]) => [day, mean(list.map(value)) as number]),
    );
  return {
    kd: series((l) => l.kills / Math.max(1, l.deaths)),
    adr: series((l) => l.adr),
    winRate: series((l) => (l.result === "win" ? 100 : 0)),
  };
}
