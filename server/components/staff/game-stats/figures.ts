import {
  gameTotals, kd, kpr, dpr, mapResult, openingDiff, type MapResult, type MapStats, type RowStats,
} from "@/lib/staff/games-math";
import { plural } from "@/lib/staff/overview-format";
import { TEAM, readCell, showNumber, readScore, visibleRows, type Draft, type DraftMap, type DraftRow } from "./draft";

/**
 * The numbers the form shows while the game is typed: the computed columns of the table and the block «Итог игры ·
 * считается сам». All maths is `lib/staff/games-math.ts`; this file only reads the inputs and formats the results.
 * A value that cannot be computed yet is «—», as on the board.
 */

export const DASH = "—";

const percent = (value: number | null): string => (value === null ? DASH : `${Math.round(value * 100)}%`);
const oneDecimal = (value: number | null): string => (value === null ? DASH : showNumber(Math.round(value * 10) / 10));
const twoDecimals = (value: number | null): string => (value === null ? DASH : value.toFixed(2).replace(".", ","));

/** Rounds of a map (our + their score); null until both scores are valid. */
export function roundsOfDraft(map: Pick<DraftMap, "our" | "their">): number | null {
  const our = readScore(map.our);
  const their = readScore(map.their);
  return our === null || their === null ? null : our + their;
}

export interface RowFigures {
  kd: string;
  kpr: string;
  dpr: string;
  open: string;
}

const numberOf = (row: DraftRow, key: "kills" | "deaths" | "fk" | "fd"): number | null => {
  const cell = readCell(key, row.cells[key]);
  return cell.kind === "ok" ? cell.value : null;
};

/** K/D, KPR, DPR and «Опен ±» of one line. */
export function rowFigures(map: DraftMap, row: DraftRow): RowFigures {
  const kills = numberOf(row, "kills");
  const deaths = numberOf(row, "deaths");
  const rounds = roundsOfDraft(map);
  const diff = openingDiff(numberOf(row, "fk"), numberOf(row, "fd"));
  return {
    kd: kills === null || deaths === null ? DASH : twoDecimals(kd(kills, deaths)),
    kpr: kills === null || rounds === null ? DASH : twoDecimals(kpr(kills, rounds)),
    dpr: deaths === null || rounds === null ? DASH : twoDecimals(dpr(deaths, rounds)),
    open: diff === null ? DASH : `${diff > 0 ? "+" : ""}${diff}`,
  };
}

/** The result of a map by its score; null until both scores are valid. */
export function mapResultOf(map: Pick<DraftMap, "our" | "their">): MapResult | null {
  const our = readScore(map.our);
  const their = readScore(map.their);
  return our === null || their === null ? null : mapResult(our, their);
}

/** Lines that have both K and D, the only ones that count for the team K/D. */
function countedRows(draft: Draft, map: DraftMap): RowStats[] {
  return visibleRows(draft, map).flatMap((row) => {
    const kills = numberOf(row, "kills");
    const deaths = numberOf(row, "deaths");
    if (kills === null || deaths === null) return [];
    return [{ playerId: row.playerId, kills, deaths, assists: 0, adr: 0, fk: null, fd: null }];
  });
}

export interface LiveSummary {
  maps: string;
  mapsSub: string;
  winRate: string;
  roundWinRate: string;
  rounds: string;
  pistolWinRate: string;
  avgRoundsWon: string;
  avgRoundsLost: string;
  kdLabel: string;
  kd: string;
}

/** «Итог игры · считается сам» for the draft as it is now. */
export function liveSummary(draft: Draft): LiveSummary {
  const scored: MapStats[] = draft.maps.flatMap((map) => {
    const our = readScore(map.our);
    const their = readScore(map.their);
    if (our === null || their === null) return [];
    return [{ ourScore: our, theirScore: their, pistol1: "lost", pistol2: "lost", rows: [] }];
  });
  // A pistol counts as soon as it is marked, with or without the score of its map.
  const pistols: MapStats[] = draft.maps.flatMap((map) =>
    map.pistol1 === "" || map.pistol2 === ""
      ? []
      : [{ ourScore: 0, theirScore: 0, pistol1: map.pistol1, pistol2: map.pistol2, rows: [] }],
  );
  const lines: MapStats[] = draft.maps.map((map) => ({
    ourScore: 0, theirScore: 0, pistol1: "lost", pistol2: "lost", rows: countedRows(draft, map),
  }));
  const totals = gameTotals(scored);
  const played = totals.maps;
  return {
    maps: played > 0 ? `${totals.mapsWon} : ${totals.mapsLost}${totals.mapsDrawn > 0 ? ` : ${totals.mapsDrawn}` : ""}` : DASH,
    mapsSub:
      played > 0
        ? `по картам · ${played} ${plural(played, "карта", "карты", "карт")}${totals.mapsDrawn > 0 ? " (П : П : Н)" : " (П : П)"}`
        : "введите счёт карты",
    winRate: percent(totals.winRate),
    roundWinRate: percent(totals.roundWinRate),
    rounds: played > 0 ? `${totals.roundsWon}–${totals.roundsLost}` : DASH,
    pistolWinRate: percent(gameTotals(pistols).pistolWinRate),
    avgRoundsWon: oneDecimal(totals.avgRoundsWon),
    avgRoundsLost: oneDecimal(totals.avgRoundsLost),
    kdLabel: draft.who === TEAM ? "K/D команды" : "K/D",
    kd: twoDecimals(gameTotals(lines).kd),
  };
}
