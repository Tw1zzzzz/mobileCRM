/**
 * The numbers derived from hand-entered games, in one place (the form, the period summary, the player card and the
 * statistics all use it). Pure functions; a value that cannot be computed (no rounds, no maps) is `null`, never `NaN`
 * or a made-up zero: the pages print «нет данных» for it. Rates are fractions of 1.
 *
 *  - K/D = K / max(D, 1), two decimals; KPR = K / rounds; DPR = D / rounds; opening ± = FK − FD.
 *  - Rounds of a map = our + their score. A map is won when our score is higher, lost when lower, drawn when equal.
 *  - Win Rate = won maps / maps; Round Win Rate = rounds won / rounds; Pistol Win Rate = pistols won / pistols
 *    (two per map); average rounds won / lost are per map.
 *  - Over several lines (a game, a period) the board adds kills, deaths, assists, first kills / deaths and clutches
 *    up, and takes the plain mean of ADR, KAST, UDR, multikills and flash time over the lines that have the value.
 */

export type MapResult = "win" | "loss" | "draw";
export type PistolResult = "won" | "lost";

/** One player's line on one map (the parts the maths needs). */
export interface RowStats {
  playerId: string | null;
  kills: number;
  deaths: number;
  assists: number;
  adr: number;
  fk: number | null;
  fd: number | null;
  /** The optional columns; a line that does not carry them (or left them empty) counts as «not entered». */
  kast?: number | null;
  udr?: number | null;
  multikills?: number | null;
  clutches?: number | null;
  flashTime?: number | null;
}

export interface MapStats {
  ourScore: number;
  theirScore: number;
  pistol1: PistolResult;
  pistol2: PistolResult;
  rows: readonly RowStats[];
}

export interface GameStats {
  maps: readonly MapStats[];
}

export interface StatTotals {
  maps: number;
  mapsWon: number;
  mapsLost: number;
  mapsDrawn: number;
  /** Won maps / maps. */
  winRate: number | null;
  /** Rounds of all maps (our + their score). */
  rounds: number;
  roundsWon: number;
  roundsLost: number;
  roundWinRate: number | null;
  pistols: number;
  pistolsWon: number;
  pistolWinRate: number | null;
  /** Average rounds won / lost per map. */
  avgRoundsWon: number | null;
  avgRoundsLost: number | null;
  /** Player lines counted (one per player per map). */
  lines: number;
  kills: number;
  deaths: number;
  assists: number;
  kd: number | null;
  /** Kills / deaths per round played by the counted lines. */
  kpr: number | null;
  dpr: number | null;
  /** Plain mean of the lines' ADR (as the board does), whatever the rounds of their maps. */
  adr: number | null;
  /** Sum of FK − FD over the lines that have both numbers. */
  openingDiff: number | null;
  /** Sums over the lines that entered the value; null when no line did. */
  fk: number | null;
  fd: number | null;
  clutches: number | null;
  /** Plain means over the lines that entered the value; null when no line did. */
  kast: number | null;
  udr: number | null;
  multikills: number | null;
  flashTime: number | null;
}

export type PeriodTotals = StatTotals & { games: number };

const ratio = (part: number, whole: number): number | null => (whole > 0 ? part / whole : null);

export function mapResult(ourScore: number, theirScore: number): MapResult {
  if (ourScore > theirScore) return "win";
  if (ourScore < theirScore) return "loss";
  return "draw";
}

export const roundsOf = (map: Pick<MapStats, "ourScore" | "theirScore">): number => map.ourScore + map.theirScore;

/** K / max(D, 1) to two decimals. */
export function kd(kills: number, deaths: number): number {
  return Math.round((kills / Math.max(deaths, 1)) * 100) / 100;
}

export const kpr = (kills: number, rounds: number): number | null => ratio(kills, rounds);
export const dpr = (deaths: number, rounds: number): number | null => ratio(deaths, rounds);

/** FK − FD; null when either number was not entered. */
export function openingDiff(fk: number | null, fd: number | null): number | null {
  return fk === null || fd === null ? null : fk - fd;
}

const sum = (values: readonly number[]): number => values.reduce((total, value) => total + value, 0);

/** The numbers among `values`; the entered ones only. */
const entered = (values: readonly (number | null | undefined)[]): number[] =>
  values.filter((v): v is number => typeof v === "number");
const total = (values: readonly (number | null | undefined)[]): number | null => {
  const list = entered(values);
  return list.length > 0 ? sum(list) : null;
};
const average = (values: readonly (number | null | undefined)[]): number | null => {
  const list = entered(values);
  return list.length > 0 ? sum(list) / list.length : null;
};

/**
 * Totals of the given maps. With `playerId` only the maps that player played and only their lines count; without
 * it every line of every map does (the lineup as a whole).
 */
export function gameTotals(maps: readonly MapStats[], playerId?: string): StatTotals {
  const played = playerId === undefined ? maps : maps.filter((m) => m.rows.some((r) => r.playerId === playerId));
  const lines = played.flatMap((m) => {
    const rounds = roundsOf(m);
    return m.rows.filter((r) => playerId === undefined || r.playerId === playerId).map((row) => ({ row, rounds }));
  });
  const mapCount = played.length;
  const results = played.map((m) => mapResult(m.ourScore, m.theirScore));
  const mapsWon = results.filter((r) => r === "win").length;
  const roundsWon = sum(played.map((m) => m.ourScore));
  const roundsLost = sum(played.map((m) => m.theirScore));
  const pistols = mapCount * 2;
  const pistolsWon = sum(played.map((m) => Number(m.pistol1 === "won") + Number(m.pistol2 === "won")));
  const kills = sum(lines.map((l) => l.row.kills));
  const deaths = sum(lines.map((l) => l.row.deaths));
  const lineRounds = sum(lines.map((l) => l.rounds));
  const withBoth = lines.flatMap((l) => {
    const diff = openingDiff(l.row.fk, l.row.fd);
    return diff === null ? [] : [diff];
  });
  return {
    maps: mapCount,
    mapsWon,
    mapsLost: results.filter((r) => r === "loss").length,
    mapsDrawn: results.filter((r) => r === "draw").length,
    winRate: ratio(mapsWon, mapCount),
    rounds: roundsWon + roundsLost,
    roundsWon,
    roundsLost,
    roundWinRate: ratio(roundsWon, roundsWon + roundsLost),
    pistols,
    pistolsWon,
    pistolWinRate: ratio(pistolsWon, pistols),
    avgRoundsWon: ratio(roundsWon, mapCount),
    avgRoundsLost: ratio(roundsLost, mapCount),
    lines: lines.length,
    kills,
    deaths,
    assists: sum(lines.map((l) => l.row.assists)),
    kd: lines.length > 0 ? kd(kills, deaths) : null,
    kpr: kpr(kills, lineRounds),
    dpr: dpr(deaths, lineRounds),
    adr: average(lines.map((l) => l.row.adr)),
    openingDiff: withBoth.length > 0 ? sum(withBoth) : null,
    fk: total(lines.map((l) => l.row.fk)),
    fd: total(lines.map((l) => l.row.fd)),
    clutches: total(lines.map((l) => l.row.clutches)),
    kast: average(lines.map((l) => l.row.kast)),
    udr: average(lines.map((l) => l.row.udr)),
    multikills: average(lines.map((l) => l.row.multikills)),
    flashTime: average(lines.map((l) => l.row.flashTime)),
  };
}

/** Totals over the maps of all the given games; `games` counts those with at least one counted map. */
export function periodTotals(games: readonly GameStats[], playerId?: string): PeriodTotals {
  const counted = playerId === undefined
    ? games.filter((g) => g.maps.length > 0)
    : games.filter((g) => g.maps.some((m) => m.rows.some((r) => r.playerId === playerId)));
  return { ...gameTotals(games.flatMap((g) => g.maps), playerId), games: counted.length };
}
