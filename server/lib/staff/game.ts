/**
 * The player card's «Игра» tiles and the per-day match series, from Faceit matches.
 *
 * Pure functions. Days are calendar days in the staff zone `tz` (a match is a real instant). Unavailable tiles are
 * `null`: Faceit's per-match stats give no KAST, first deaths, flash time, draws or pistol rounds, and a value
 * without data is never a zero. The Kotlin app computes the same base numbers (`GameSummary`, `TeamAnalytics.stats`);
 * `FaceitParityTest.kt` checks them against `test/fixtures/faceit-matches.json`.
 */
import type { FaceitMatch } from "@/lib/faceit/mapper";
import { dayOf, mean, type Day } from "@/lib/staff/metrics";
import type { DaySeries } from "@/lib/staff/series";

/** Raw numbers, unrounded; the card formats them. `null` = no data. */
export interface GameTiles {
  matches: number;
  wins: number | null;
  losses: number | null;
  /** Percent, 0..100. */
  winRate: number | null;
  kills: number | null;
  deaths: number | null;
  assists: number | null;
  /** Total kills / max(1, total deaths). */
  kd: number | null;
  /** Mean of the matches' ADR. */
  adr: number | null;
  /** Kills (deaths) per round over all rounds. */
  kpr: number | null;
  deathsPerRound: number | null;
  avgKR: number | null;
  /** Mean of the matches' kills / max(1, deaths). */
  avgKD: number | null;
  kast: null;
  firstKills: number | null;
  firstDeaths: null;
  /** Utility damage per round. */
  udr: number | null;
  /** Entry duels won minus lost. */
  openDuelDiff: number | null;
  /** Multi-kill rounds (2K..5K) per match. */
  avgMultikills: number | null;
  clutchesWon: number | null;
  avgFlashTime: null;
  draws: null;
  /** Rounds of the matches that have a score. */
  rounds: number | null;
  roundsWon: number | null;
  roundWinRate: number | null;
  avgRoundsWon: number | null;
  avgRoundsLost: number | null;
  pistolWon: null;
  pistolRounds: null;
  pistolWinRate: null;
}

/** The tiles Faceit cannot give, always `null`. */
export const UNAVAILABLE_TILES = ["kast", "firstDeaths", "avgFlashTime", "draws", "pistolWon", "pistolRounds", "pistolWinRate"] as const;

const sum = (values: readonly number[]): number => values.reduce((total, value) => total + value, 0);

function perMatchKd(match: FaceitMatch): number {
  return match.kills / Math.max(1, match.deaths);
}

function inWindow(matches: readonly FaceitMatch[], from: Day, to: Day, tz: string): FaceitMatch[] {
  return matches.filter((match) => {
    const day = dayOf(new Date(match.finishedAt), tz);
    return day >= from && day <= to;
  });
}

/** Sum of `pick` over the matches that have it; null when none does. */
function sumWhere(matches: readonly FaceitMatch[], pick: (match: FaceitMatch) => number | undefined): number | null {
  const values = matches.map(pick).filter((value): value is number => value !== undefined);
  return values.length > 0 ? sum(values) : null;
}

function emptyTiles(): GameTiles {
  return {
    matches: 0, wins: null, losses: null, winRate: null, kills: null, deaths: null, assists: null, kd: null, adr: null,
    kpr: null, deathsPerRound: null, avgKR: null, avgKD: null, kast: null, firstKills: null, firstDeaths: null,
    udr: null, openDuelDiff: null, avgMultikills: null, clutchesWon: null, avgFlashTime: null, draws: null,
    rounds: null, roundsWon: null, roundWinRate: null, avgRoundsWon: null, avgRoundsLost: null,
    pistolWon: null, pistolRounds: null, pistolWinRate: null,
  };
}

/** Every tile of the «Игра» tab for the matches finished on the days `from..to` (inclusive) in `tz`. */
export function gameSummary(matches: readonly FaceitMatch[], from: Day, to: Day, tz: string): GameTiles {
  const list = inWindow(matches, from, to, tz);
  if (list.length === 0) return emptyTiles();

  const count = list.length;
  const kills = sum(list.map((m) => m.kills));
  const deaths = sum(list.map((m) => m.deaths));
  const rounds = sum(list.map((m) => m.rounds));
  const wins = list.filter((m) => m.won).length;

  const scored = list.filter((m) => m.scoreOwn + m.scoreOther > 0);
  const scoredRounds = sum(scored.map((m) => m.rounds));
  const roundsWon = sum(scored.map((m) => m.scoreOwn));

  const withEntry = list.filter((m) => m.entryCount !== undefined);
  const withMulti = list.filter((m) => m.multiKills !== undefined);
  const withDamage = list.filter((m) => m.utilityDamage !== undefined);
  const damageRounds = sum(withDamage.map((m) => m.rounds));

  return {
    ...emptyTiles(),
    matches: count,
    wins,
    losses: count - wins,
    winRate: (wins / count) * 100,
    kills,
    deaths,
    assists: sum(list.map((m) => m.assists)),
    kd: kills / Math.max(1, deaths),
    adr: sum(list.map((m) => m.adr)) / count,
    kpr: kills / rounds,
    deathsPerRound: deaths / rounds,
    avgKR: kills / rounds,
    avgKD: sum(list.map(perMatchKd)) / count,
    firstKills: sumWhere(list, (m) => m.firstKills),
    udr: withDamage.length > 0 ? sum(withDamage.map((m) => m.utilityDamage ?? 0)) / damageRounds : null,
    openDuelDiff: withEntry.length > 0 ? sum(withEntry.map((m) => 2 * (m.entryWins ?? 0) - (m.entryCount ?? 0))) : null,
    avgMultikills:
      withMulti.length > 0
        ? sum(withMulti.map((m) => (m.multiKills ? m.multiKills.k2 + m.multiKills.k3 + m.multiKills.k4 + m.multiKills.k5 : 0))) /
          withMulti.length
        : null,
    clutchesWon: sumWhere(list, (m) => m.clutchWins),
    rounds: scored.length > 0 ? scoredRounds : null,
    roundsWon: scored.length > 0 ? roundsWon : null,
    roundWinRate: scored.length > 0 ? (roundsWon / scoredRounds) * 100 : null,
    avgRoundsWon: scored.length > 0 ? roundsWon / scored.length : null,
    avgRoundsLost: scored.length > 0 ? (scoredRounds - roundsWon) / scored.length : null,
  };
}

/**
 * Per-day means over the matches of each day: K/D (kills / max(1, deaths) per match, as the app's `GameMatch.kd`),
 * ADR, and win rate (100 for a win, 0 for a loss). A day without matches has no key.
 */
export function matchDaily(
  matches: readonly FaceitMatch[],
  from: Day,
  to: Day,
  tz: string,
): { kd: DaySeries; adr: DaySeries; winRate: DaySeries } {
  const byDay = new Map<Day, FaceitMatch[]>();
  for (const match of inWindow(matches, from, to, tz)) {
    const day = dayOf(new Date(match.finishedAt), tz);
    byDay.set(day, [...(byDay.get(day) ?? []), match]);
  }
  const series = (value: (match: FaceitMatch) => number): DaySeries =>
    Object.fromEntries(
      [...byDay.entries()]
        .sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([day, list]) => [day, mean(list.map(value)) as number]),
    );
  return {
    kd: series(perMatchKd),
    adr: series((m) => m.adr),
    winRate: series((m) => (m.won ? 100 : 0)),
  };
}
