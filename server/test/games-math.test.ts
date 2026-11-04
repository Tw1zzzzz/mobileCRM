import { describe, expect, it } from "vitest";
import {
  gameTotals, kd, kpr, dpr, mapResult, openingDiff, periodTotals, type MapStats, type RowStats,
} from "../lib/staff/games-math";

const row = (over: Partial<RowStats> = {}): RowStats => ({
  playerId: "p1", kills: 20, deaths: 10, assists: 4, adr: 80, fk: null, fd: null,
  ...over,
});
const map = (our: number, their: number, over: Partial<MapStats> = {}): MapStats => ({
  ourScore: our, theirScore: their, pistol1: "won", pistol2: "lost", rows: [row()], ...over,
});

describe("mapResult", () => {
  it("winLossDraw", () => {
    expect(mapResult(13, 7)).toBe("win");
    expect(mapResult(5, 13)).toBe("loss");
    expect(mapResult(15, 15)).toBe("draw");
  });
});

describe("per-player numbers", () => {
  it("kdIsKillsOverMaxDeathsRoundedToTwoDecimals", () => {
    expect(kd(20, 10)).toBe(2);
    expect(kd(10, 3)).toBe(3.33);
    expect(kd(2, 3)).toBe(0.67);
  });

  it("kdWithZeroDeathsDividesByOne", () => {
    expect(kd(15, 0)).toBe(15);
    expect(kd(0, 0)).toBe(0);
  });

  it("kprAndDprNeedRounds", () => {
    expect(kpr(21, 24)).toBeCloseTo(0.875);
    expect(dpr(12, 24)).toBeCloseTo(0.5);
    expect(kpr(10, 0)).toBeNull();
    expect(dpr(10, 0)).toBeNull();
  });

  it("openingDiffNeedsBothNumbers", () => {
    expect(openingDiff(5, 2)).toBe(3);
    expect(openingDiff(1, 4)).toBe(-3);
    expect(openingDiff(null, 2)).toBeNull();
    expect(openingDiff(3, null)).toBeNull();
  });
});

describe("gameTotals", () => {
  it("countsMapsRoundsAndPistols", () => {
    const t = gameTotals([map(13, 7, { pistol1: "won", pistol2: "won" }), map(10, 13, { pistol1: "lost", pistol2: "won" })]);
    expect(t).toMatchObject({
      maps: 2, mapsWon: 1, mapsLost: 1, mapsDrawn: 0, winRate: 0.5,
      rounds: 43, roundsWon: 23, roundsLost: 20, pistols: 4, pistolsWon: 3,
    });
    expect(t.roundWinRate).toBeCloseTo(23 / 43);
    expect(t.pistolWinRate).toBe(0.75);
    expect(t.avgRoundsWon).toBe(11.5);
    expect(t.avgRoundsLost).toBe(10);
  });

  it("drawIsNeitherWinNorLoss", () => {
    const t = gameTotals([map(15, 15)]);
    expect(t).toMatchObject({ mapsWon: 0, mapsLost: 0, mapsDrawn: 1, winRate: 0 });
  });

  it("zeroRoundsGiveNoRatesInsteadOfNaN", () => {
    const t = gameTotals([map(0, 0)]);
    expect(t.rounds).toBe(0);
    expect(t.roundWinRate).toBeNull();
    expect(t.kpr).toBeNull();
    expect(t.dpr).toBeNull();
    expect(t.adr).toBe(80); // the mean of the entered ADRs does not need rounds
    expect(t.winRate).toBe(0);
  });

  it("noMapsGiveNoNumbers", () => {
    const t = gameTotals([]);
    expect(t).toMatchObject({ maps: 0, rounds: 0, winRate: null, roundWinRate: null, pistolWinRate: null });
    expect(t.avgRoundsWon).toBeNull();
    expect(t.kd).toBeNull();
    expect(t.adr).toBeNull();
    expect(t.openingDiff).toBeNull();
  });

  it("sumsLinesAndAveragesAdrOverTheLines", () => {
    const t = gameTotals([
      map(13, 7, { rows: [row({ kills: 20, deaths: 10, assists: 3, adr: 90 }), row({ playerId: "p2", kills: 10, deaths: 5, assists: 1, adr: 60 })] }),
      map(3, 13, { rows: [row({ kills: 6, deaths: 14, assists: 2, adr: 40 })] }),
    ]);
    expect(t).toMatchObject({ lines: 3, kills: 36, deaths: 29, assists: 6 });
    expect(t.kd).toBe(1.24);
    // The board's «ADR» is the plain mean of the lines' ADR, not weighed by the rounds of their maps.
    expect(t.adr).toBeCloseTo((90 + 60 + 40) / 3);
    // lines: 20 rounds x2 and 16 rounds x1
    expect(t.kpr).toBeCloseTo(36 / 56);
    expect(t.dpr).toBeCloseTo(29 / 56);
  });

  it("optionalColumnsAreSummedOrAveragedOverTheLinesThatHaveThem", () => {
    const t = gameTotals([
      map(13, 7, {
        rows: [
          row({ fk: 4, fd: 1, kast: 80, udr: 12, multikills: 1, clutches: 2, flashTime: 2 }),
          row({ fk: 2, fd: null, kast: 70, udr: null, multikills: 0.5, clutches: null, flashTime: null }),
          row(),
        ],
      }),
    ]);
    expect(t).toMatchObject({ fk: 6, fd: 1, clutches: 2, udr: 12, flashTime: 2 });
    expect(t.kast).toBeCloseTo(75);
    expect(t.multikills).toBeCloseTo(0.75);
  });

  it("optionalColumnsNobodyEnteredAreNullNotZero", () => {
    const t = gameTotals([map(13, 7)]);
    expect(t).toMatchObject({ fk: null, fd: null, clutches: null, kast: null, udr: null, multikills: null, flashTime: null });
  });

  it("openingDiffSumsOnlyLinesThatHaveBoth", () => {
    const t = gameTotals([map(13, 7, { rows: [row({ fk: 4, fd: 1 }), row({ fk: 2, fd: null }), row({ fk: null, fd: null })] })]);
    expect(t.openingDiff).toBe(3);
  });

  it("aPlayerFilterKeepsOnlyTheirMapsAndLines", () => {
    const withBoth = map(13, 7, { rows: [row({ playerId: "p1", kills: 20 }), row({ playerId: "p2", kills: 5 })] });
    const withoutP1 = map(2, 13, { rows: [row({ playerId: "p2", kills: 1 })] });
    const t = gameTotals([withBoth, withoutP1], "p1");
    expect(t).toMatchObject({ maps: 1, mapsWon: 1, rounds: 20, lines: 1, kills: 20 });
    expect(gameTotals([withoutP1], "p1").maps).toBe(0);
  });
});

describe("periodTotals", () => {
  it("addsUpMapsAcrossGames", () => {
    const g1 = { maps: [map(13, 7), map(8, 13)] };
    const g2 = { maps: [map(13, 11)] };
    const t = periodTotals([g1, g2]);
    expect(t.games).toBe(2);
    expect(t).toMatchObject({ maps: 3, mapsWon: 2, mapsLost: 1, rounds: 65, roundsWon: 34, lines: 3, kills: 60 });
    expect(t.winRate).toBeCloseTo(2 / 3);
  });

  it("countsOnlyGamesWithTheFilteredPlayer", () => {
    const g1 = { maps: [map(13, 7, { rows: [row({ playerId: "p1" })] })] };
    const g2 = { maps: [map(13, 7, { rows: [row({ playerId: "p2" })] })] };
    expect(periodTotals([g1, g2], "p1").games).toBe(1);
  });

  it("noGamesMeansZeroGamesAndNoRates", () => {
    const t = periodTotals([]);
    expect(t.games).toBe(0);
    expect(t.winRate).toBeNull();
  });
});
