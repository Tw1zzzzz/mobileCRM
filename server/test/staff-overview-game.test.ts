import { describe, expect, it } from "vitest";
import type { GameLine } from "@/lib/staff/game-lines";
import { gameMetrics, gameWindow } from "@/lib/staff/overview-game";

const TODAY = "2026-10-15";

function line(day: string, kills: number, deaths: number, result: GameLine["result"], key = `${day}-${kills}`): GameLine {
  return {
    key, source: "site", day, kills, deaths, assists: 0, adr: 80, rounds: 24, ourScore: 13, theirScore: 11, result,
    firstKills: null, firstDeaths: null, openDuelDiff: null, kast: null, udr: null, multikills: null, clutches: null,
    flashTime: null, pistolsWon: null,
  };
}

describe("gameWindow", () => {
  it("coversThisWeekAndTheWeekBefore", () => {
    expect(gameWindow(TODAY)).toEqual({ from: "2026-10-02", to: "2026-10-15" });
  });
});

describe("gameMetrics", () => {
  const players = [
    // this week: 2 maps on the 15th (K/D 2 and 1, one win), one lost map on the 13th; last week: one won map
    { id: "p1", lines: [line("2026-10-15", 20, 10, "win"), line("2026-10-15", 10, 10, "loss"), line("2026-10-13", 10, 20, "loss"), line("2026-10-05", 15, 10, "win")] },
    // this week: one won map on the 15th
    { id: "p2", lines: [line("2026-10-15", 30, 10, "win")] },
  ];

  it("teamMeansThePlayersDailyMeans", () => {
    const [kd, winRate] = gameMetrics(players, "team", TODAY);
    expect(kd.key).toBe("kd");
    // 15th: p1 1.5, p2 3 → 2.25; 13th: 0.5 → week mean (2.25 + 0.5) / 2
    expect(kd.series).toEqual([null, null, null, null, 0.5, null, 2.25]);
    expect(kd.value).toBe(1.38);
    expect(kd.previous).toBe(1.5);
    expect(kd.direction).toBe("down");
    expect(kd.pill).toBe("good");
    // 15th: p1 50, p2 100 → 75; 13th: 0 → 37.5; the week before: 100
    expect(winRate.value).toBe(37.5);
    expect(winRate.previous).toBe(100);
    expect(winRate.delta).toBe(-62.5);
    expect(winRate.pill).toBe("bad");
  });

  it("aPlayerSeesOnlyTheirLines", () => {
    const [kd, winRate] = gameMetrics(players, "p2", TODAY);
    expect(kd.value).toBe(3);
    expect(winRate.value).toBe(100);
    expect(kd.previous).toBeNull();
    expect(kd.direction).toBeNull();
  });

  it("noGamesIsNoData", () => {
    const [kd, winRate] = gameMetrics([{ id: "p1", lines: [] }], "team", TODAY);
    expect(kd.value).toBeNull();
    expect(kd.pill).toBeNull();
    expect(winRate.series).toEqual([null, null, null, null, null, null, null]);
  });
});
