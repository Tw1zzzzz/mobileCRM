import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { PeriodSummary } from "@/components/staff/game-stats/PeriodSummary";
import { GAME_STATS_PATH, summaryHref, type SummaryParams } from "@/lib/staff/game-summary";
import { gameStatsReturnPath } from "@/lib/staff/return-path";
import type { Game } from "@/lib/staff/games";

describe("where deleting a game returns to", () => {
  it("keepsTheSummaryFiltersOfThePage", () => {
    const kept = `${GAME_STATS_PATH}?period=90&kind=tournament&who=abc#gs-period`;
    expect(gameStatsReturnPath(kept)).toBe(kept);
    expect(gameStatsReturnPath(GAME_STATS_PATH)).toBe(GAME_STATS_PATH);
    expect(gameStatsReturnPath(`${GAME_STATS_PATH}#gs-period`)).toBe(`${GAME_STATS_PATH}#gs-period`);
  });

  it.each([
    undefined,
    "",
    "https://evil.example/staff/game-stats",
    "//evil.example/staff/game-stats",
    "/\\evil.example",
    "/staff/players",
    "/staff/game-statsX",
    "/staff/game-stats/../players",
    " /staff/game-stats",
    "/staff/game-stats\r\nSet-Cookie: a=b",
  ])("otherwiseTheBarePage (%j)", (raw) => {
    expect(gameStatsReturnPath(raw)).toBe(GAME_STATS_PATH);
  });

  it("theConfirmFormCarriesTheFilters", () => {
    const params: SummaryParams = { period: 90, range: { from: "2026-08-06", to: "2026-11-04" }, kind: "tournament", who: "team" };
    const game = { id: "11111111-1111-4111-8111-111111111111", playedOn: "2026-11-01", kind: "tournament", opponent: null } as unknown as Game;
    const markup = renderToStaticMarkup(<PeriodSummary params={params} roster={[]} games={[]} confirm={game} />);
    const expected = summaryHref(params).replace(/&/g, "&amp;");
    expect(markup).toContain(`name="returnTo" value="${expected}"`);
  });
});
