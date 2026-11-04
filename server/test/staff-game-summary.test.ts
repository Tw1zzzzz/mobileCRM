import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { requireStaff } from "../lib/staff/access";
import { loadSummaryGames, parseSummaryParams } from "../lib/staff/game-summary";
import { buildSummary } from "../lib/staff/game-summary-table";
import { deleteGame, saveGame, type GameInput, type GameMapInput, type GameRowInput } from "../lib/staff/games";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam } from "./staff-card-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const line = (over: Partial<GameRowInput> = {}): GameRowInput => ({
  nickname: "ringer", kills: 20, deaths: 10, assists: 4, adr: 80, ...over,
});
const mapOf = (over: Partial<GameMapInput> = {}): GameMapInput => ({
  map: "mirage", ourScore: 13, theirScore: 7, pistol1: "won", pistol2: "lost", rows: [line()], ...over,
});
const gameOf = (over: Partial<GameInput> = {}): GameInput => ({
  kind: "pracc", playedOn: "2026-10-25", opponent: null, maps: [mapOf()], ...over,
});

async function seed() {
  const t = await seedCardTeam(deps);
  const ctx = t.ctx;
  const roster = [t.ids.vega, t.ids.nova];
  const pracc = await saveGame(deps, ctx, gameOf({
    playedOn: "2026-10-20",
    maps: [mapOf({ rows: [line({ nickname: undefined, playerId: t.ids.vega, kills: 30, deaths: 10 })] })],
  }));
  const tournament = await saveGame(deps, ctx, gameOf({
    kind: "tournament", playedOn: "2026-10-28", opponent: "Natus Vincere",
    maps: [mapOf({
      ourScore: 5, theirScore: 13,
      rows: [line({ nickname: undefined, playerId: t.ids.vega, kills: 10, deaths: 10 }), line({ nickname: undefined, playerId: t.ids.nova, kills: 5, deaths: 10 })],
    })],
  }));
  const old = await saveGame(deps, ctx, gameOf({ playedOn: "2026-08-10" }));
  return { ...t, ctx, roster, pracc, tournament, old };
}

const NOVEMBER = "2026-11-04";

describe("the summary of the team's games", () => {
  it("filterByKindAndPlayer", async () => {
    const t = await seed();
    const load = (query: Record<string, string>) =>
      loadSummaryGames(deps, t.ctx, parseSummaryParams({ period: "90", ...query }, t.roster, NOVEMBER));
    expect((await load({})).map((g) => g.id)).toEqual([t.tournament, t.pracc, t.old]);
    expect((await load({ kind: "pracc" })).map((g) => g.id)).toEqual([t.pracc, t.old]);
    expect((await load({ kind: "tournament" })).map((g) => g.id)).toEqual([t.tournament]);
    expect((await load({ who: t.ids.nova })).map((g) => g.id)).toEqual([t.tournament]);
    expect((await load({ kind: "pracc", who: t.ids.nova }))).toEqual([]);
    // Another team's player (or garbage) is «everyone», not a leak and not an error.
    expect(await load({ who: t.ids.otherStaff })).toHaveLength(3);
  });

  it("thePeriodCutsByDay", async () => {
    const t = await seed();
    const games = await loadSummaryGames(deps, t.ctx, parseSummaryParams({ period: "30" }, t.roster, NOVEMBER));
    expect(games.map((g) => g.id)).toEqual([t.tournament, t.pracc]);
    const custom = parseSummaryParams({ period: "custom", from: "2026-08-10", to: "2026-08-10" }, t.roster, NOVEMBER);
    expect((await loadSummaryGames(deps, t.ctx, custom)).map((g) => g.id)).toEqual([t.old]);
  });

  it("aPlayersSummaryCountsOnlyTheirLines", async () => {
    const t = await seed();
    const params = parseSummaryParams({ period: "30", who: t.ids.vega }, t.roster, NOVEMBER);
    const summary = buildSummary(await loadSummaryGames(deps, t.ctx, params), params.who);
    const kills = summary.rows.find((r) => r.kind === "data" && r.label === "Убийства (Kills)");
    expect(kills).toMatchObject({ cells: ["40", "10", "30"] });
    expect(summary.tiles.find((tile) => tile.label === "K/D")?.value).toBe("2,00");
  });

  it("deleteGame", async () => {
    const t = await seed();
    const params = parseSummaryParams({ period: "30" }, t.roster, NOVEMBER);
    expect(await deleteGame(deps, t.ctx, t.tournament)).toBe(true);
    expect((await loadSummaryGames(deps, t.ctx, params)).map((g) => g.id)).toEqual([t.pracc]);
    // A second delete, and another team's staff deleting this team's game, change nothing.
    expect(await deleteGame(deps, t.ctx, t.tournament)).toBe(false);
    const other = await requireStaff(deps, t.ids.otherStaff);
    expect(await deleteGame(deps, other, t.pracc)).toBe(false);
    expect(await loadSummaryGames(deps, t.ctx, params)).toHaveLength(1);
  });
});
