import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { ApiError } from "../lib/http";
import { requireStaff } from "../lib/staff/access";
import {
  deleteGame, getGame, listGames, saveGame, type GameInput, type GameMapInput, type GameRowInput,
} from "../lib/staff/games";
import { removeMember } from "../lib/team/members";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const G1 = "b1111111-1111-4111-8111-111111111111";
const G2 = "b2222222-2222-4222-8222-222222222222";

const line = (over: Partial<GameRowInput> = {}): GameRowInput => ({
  nickname: "ringer", kills: 20, deaths: 10, assists: 4, adr: 82.5, ...over,
});
const mapOf = (over: Partial<GameMapInput> = {}): GameMapInput => ({
  map: "mirage", ourScore: 13, theirScore: 7, pistol1: "won", pistol2: "lost", rows: [line()], ...over,
});
const gameOf = (over: Partial<GameInput> = {}): GameInput => ({
  kind: "pracc", playedOn: "2026-10-25", opponent: "Natus Vincere", maps: [mapOf()], ...over,
});
const withRow = (over: Partial<GameRowInput>): GameInput => gameOf({ maps: [mapOf({ rows: [line(over)] })] });
const withMap = (over: Partial<GameMapInput>): GameInput => gameOf({ maps: [mapOf(over)] });

async function refusal(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (e) {
    if (e instanceof ApiError) return e;
    throw e;
  }
  throw new Error("expected the call to be refused");
}

const count = async (table: string) =>
  (await deps.sql.unsafe(`select count(*)::int as n from ${table}`))[0].n as number;

async function seed(): Promise<CardTeam & { otherCtx: CardTeam["ctx"] }> {
  const t = await seedCardTeam(deps);
  return { ...t, otherCtx: await requireStaff(deps, t.ids.otherStaff) };
}

describe("validation limits", () => {
  it("acceptsEveryUpperBoundAtOnce", async () => {
    const t = await seed();
    const maps = Array.from({ length: 5 }, (_, i) =>
      mapOf({
        map: (["mirage", "inferno", "nuke", "ancient", "overpass"] as const)[i], ourScore: 30, theirScore: 30,
        rows: Array.from({ length: 10 }, (_, j) => line({
          nickname: `p${j}`.padEnd(40, "x"), kills: 99, deaths: 99, assists: 99, adr: 300, fk: 30, fd: 30,
          multikills: 10, clutches: 20, flashTime: 60,
        })),
      }));
    const id = await saveGame(deps, t.ctx, gameOf({ opponent: "o".repeat(60), maps }));
    const game = await getGame(deps, t.ctx, id);
    expect(game?.maps).toHaveLength(5);
    expect(game?.maps[4].rows).toHaveLength(10);
    expect(game?.maps[0].rows[0]).toMatchObject({ kills: 99, adr: 300, fk: 30, multikills: 10, clutches: 20, flashTime: 60 });
  });

  it("acceptsEveryLowerBoundAndTheExtraMaps", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({
      opponent: null, maps: [mapOf({ map: "vertigo", ourScore: 0, theirScore: 0, rows: [line({ nickname: "x", kills: 0, deaths: 0, assists: 0, adr: 0, fk: 0, fd: 0, multikills: 0, clutches: 0, flashTime: 0 })] })],
    }));
    const game = await getGame(deps, t.ctx, id);
    expect(game).toMatchObject({ opponent: null, maps: [{ map: "vertigo", ourScore: 0, theirScore: 0 }] });
    expect(game?.maps[0].rows[0]).toMatchObject({ kills: 0, adr: 0, fk: 0, fd: 0, multikills: 0, clutches: 0, flashTime: 0 });
  });

  const cases: [string, () => GameInput][] = [
    ["kind", () => gameOf({ kind: "scrim" as never })],
    ["date format", () => gameOf({ playedOn: "25.10.2026" })],
    ["date that does not exist", () => gameOf({ playedOn: "2026-02-30" })],
    ["opponent over 60", () => gameOf({ opponent: "o".repeat(61) })],
    ["no maps", () => gameOf({ maps: [] })],
    ["six maps", () => gameOf({ maps: Array.from({ length: 6 }, () => mapOf()) })],
    ["unknown map", () => withMap({ map: "cache" as never })],
    ["score below 0", () => withMap({ ourScore: -1 })],
    ["our score over 30", () => withMap({ ourScore: 31 })],
    ["their score over 30", () => withMap({ theirScore: 31 })],
    ["fractional score", () => withMap({ ourScore: 12.5 })],
    ["pistol 1", () => withMap({ pistol1: "draw" as never })],
    ["pistol 2", () => withMap({ pistol2: undefined as never })],
    ["no player rows", () => withMap({ rows: [] })],
    ["eleven player rows", () => withMap({ rows: Array.from({ length: 11 }, (_, i) => line({ nickname: `n${i}` })) })],
    ["kills over 99", () => withRow({ kills: 100 })],
    ["kills below 0", () => withRow({ kills: -1 })],
    ["fractional kills", () => withRow({ kills: 1.5 })],
    ["deaths over 99", () => withRow({ deaths: 100 })],
    ["assists over 99", () => withRow({ assists: 100 })],
    ["adr over 300", () => withRow({ adr: 300.1 })],
    ["adr below 0", () => withRow({ adr: -0.1 })],
    ["adr with two decimals", () => withRow({ adr: 78.46 })],
    ["fk over 30", () => withRow({ fk: 31 })],
    ["fd over 30", () => withRow({ fd: 31 })],
    ["multikills over 10", () => withRow({ multikills: 10.1 })],
    ["multikills with two decimals", () => withRow({ multikills: 1.25 })],
    ["clutches over 20", () => withRow({ clutches: 21 })],
    ["flash time over 60", () => withRow({ flashTime: 60.1 })],
    ["flash time with two decimals", () => withRow({ flashTime: 3.33 })],
    ["empty nickname", () => withRow({ nickname: "   " })],
    ["nickname over 40", () => withRow({ nickname: "n".repeat(41) })],
    ["neither player nor nickname", () => withRow({ nickname: undefined })],
    ["both player and nickname", () => withRow({ playerId: G2 })],
    ["player id that is not a uuid", () => withRow({ nickname: undefined, playerId: "vega" })],
  ];
  it.each(cases)("refuses %s", async (_label, build) => {
    const t = await seed();
    const error = await refusal(saveGame(deps, t.ctx, build()));
    expect(error).toMatchObject({ code: "validation_failed", status: 422 });
    expect(await count("team_games")).toBe(0);
  });

  it("refusesTheSamePlayerTwiceOnOneMap", async () => {
    const t = await seed();
    const rows = [line({ nickname: undefined, playerId: t.ids.vega }), line({ nickname: undefined, playerId: t.ids.vega })];
    expect(await refusal(saveGame(deps, t.ctx, gameOf({ maps: [mapOf({ rows })] })))).toMatchObject({ code: "validation_failed" });
  });

  it("trimsTheOpponentAndTurnsBlankIntoNone", async () => {
    const t = await seed();
    const named = await saveGame(deps, t.ctx, gameOf({ opponent: "  FaZe  " }));
    const blank = await saveGame(deps, t.ctx, gameOf({ opponent: "   " }));
    expect((await getGame(deps, t.ctx, named))?.opponent).toBe("FaZe");
    expect((await getGame(deps, t.ctx, blank))?.opponent).toBeNull();
  });
});

describe("saving and reading", () => {
  it("storesAGameAndReturnsItInOrder", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({
      kind: "tournament", playedOn: "2026-10-20",
      maps: [mapOf({ map: "nuke" }), mapOf({ map: "anubis", ourScore: 9, theirScore: 13, pistol1: "lost", pistol2: "lost" })],
    }));
    const game = await getGame(deps, t.ctx, id);
    expect(game).toMatchObject({ id, kind: "tournament", playedOn: "2026-10-20", opponent: "Natus Vincere", createdBy: t.ids.staff });
    expect(game?.maps.map((m) => [m.position, m.map, m.ourScore, m.theirScore, m.pistol1, m.pistol2])).toEqual([
      [0, "nuke", 13, 7, "won", "lost"], [1, "anubis", 9, 13, "lost", "lost"],
    ]);
    expect(game?.maps[0].rows[0]).toMatchObject({ nickname: "ringer", kills: 20, adr: 82.5, fk: null, fd: null, multikills: null, clutches: null, flashTime: null });
  });

  it("keepsTheIdTheCallerChose", async () => {
    const t = await seed();
    expect(await saveGame(deps, t.ctx, gameOf({ id: G1.toUpperCase() }))).toBe(G1);
  });

  it("standInByNickname", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({
      maps: [mapOf({ rows: [line({ nickname: undefined, playerId: t.ids.vega }), line({ nickname: "  Stand-In  " })] })],
    }));
    const rows = (await getGame(deps, t.ctx, id))!.maps[0].rows;
    expect(rows.map((r) => [r.playerId, r.nickname])).toEqual([[t.ids.vega, "vega"], [null, "Stand-In"]]);
  });

  it("aRosterRowTakesTheNicknameFromTheRoster", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId: t.ids.nova }));
    expect((await getGame(deps, t.ctx, id))!.maps[0].rows[0]).toMatchObject({ playerId: t.ids.nova, nickname: "nova" });
  });

  it("removedPlayerRowsKeepNickname", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId: t.ids.vega }));
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.vega);
    expect((await getGame(deps, t.ctx, id))!.maps[0].rows[0]).toMatchObject({ playerId: t.ids.vega, nickname: "vega" });
    // The account goes away altogether: the line stays under the nickname, without a link.
    await deps.sql`delete from users where id = ${t.ids.vega}`;
    expect((await getGame(deps, t.ctx, id))!.maps[0].rows[0]).toMatchObject({ playerId: null, nickname: "vega" });
  });

  it("aGameWithARemovedPlayerCanStillBeEdited", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId: t.ids.vega }));
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.vega);
    await saveGame(deps, t.ctx, { ...withRow({ nickname: undefined, playerId: t.ids.vega, kills: 30 }), id });
    expect((await getGame(deps, t.ctx, id))!.maps[0].rows[0]).toMatchObject({ playerId: t.ids.vega, nickname: "vega", kills: 30 });
    // ...but the removed player cannot be put into another game.
    expect(await refusal(saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId: t.ids.vega })))).toMatchObject({ code: "validation_failed" });
  });

  it("replacesAGameByIdAndKeepsItsOrigin", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({ id: G1, maps: [mapOf(), mapOf({ map: "inferno" })] }));
    const before = (await getGame(deps, t.ctx, id))!;
    deps.advance(60_000);
    const again = await saveGame(deps, t.ctx, gameOf({ id: G1, kind: "tournament", opponent: null, maps: [mapOf({ map: "train", ourScore: 5 })] }));
    expect(again).toBe(id);
    const after = (await getGame(deps, t.ctx, id))!;
    expect(after).toMatchObject({ kind: "tournament", opponent: null, createdAt: before.createdAt, createdBy: t.ids.staff });
    expect(after.updatedAt.getTime()).toBeGreaterThan(before.updatedAt.getTime());
    expect(after.maps.map((m) => [m.map, m.ourScore])).toEqual([["train", 5]]);
    expect(await count("team_games")).toBe(1);
    expect(await count("team_game_maps")).toBe(1);
    expect(await count("team_game_rows")).toBe(1);
  });

  it("replaceGameIsAtomic", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({ id: G1, opponent: "Before" }));
    await deps.sql.unsafe(`
      create function test_game_boom() returns trigger language plpgsql as $$ begin
        if new.nickname = 'boom' then raise exception 'boom'; end if; return new; end $$`);
    await deps.sql.unsafe("create trigger test_game_boom before insert on team_game_rows for each row execute function test_game_boom()");
    try {
      await expect(saveGame(deps, t.ctx, gameOf({ id, opponent: "After", maps: [mapOf({ map: "dust2" }), mapOf({ rows: [line({ nickname: "boom" })] })] }))).rejects.toThrow();
    } finally {
      await deps.sql.unsafe("drop trigger test_game_boom on team_game_rows");
      await deps.sql.unsafe("drop function test_game_boom()");
    }
    const game = (await getGame(deps, t.ctx, id))!;
    expect(game.opponent).toBe("Before");
    expect(game.maps.map((m) => m.map)).toEqual(["mirage"]);
    expect(game.maps[0].rows.map((r) => r.nickname)).toEqual(["ringer"]);
  });
});

describe("access", () => {
  it("gamesAreTeamScoped", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({ id: G1, opponent: "Mine" }));
    // Another team's staff sees nothing, cannot read, replace or delete it, and cannot reuse its id.
    expect(await getGame(deps, t.otherCtx, id)).toBeNull();
    expect(await listGames(deps, t.otherCtx, { from: "2026-01-01", to: "2026-12-31" })).toEqual([]);
    expect(await refusal(saveGame(deps, t.otherCtx, gameOf({ id, opponent: "Hijack" })))).toMatchObject({ code: "not_found", status: 404 });
    expect(await deleteGame(deps, t.otherCtx, id)).toBe(false);
    expect((await getGame(deps, t.ctx, id))?.opponent).toBe("Mine");
    // A roster player of another team cannot be put on our game.
    expect(await refusal(saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId: t.ids.outsider })))).toMatchObject({ code: "validation_failed" });
    // ...nor a solo player, a staff member, or an unknown id.
    for (const playerId of [t.ids.solo, t.ids.staff, G2]) {
      expect(await refusal(saveGame(deps, t.ctx, withRow({ nickname: undefined, playerId })))).toMatchObject({ code: "validation_failed" });
    }
    expect(await count("team_games")).toBe(1);
  });

  it("onlyStaffWrite", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({ id: G1 }));
    const playerCtx = { ...t.ctx, userId: t.ids.vega };
    const soloCtx = { ...t.ctx, userId: t.ids.solo };
    for (const ctx of [playerCtx, soloCtx]) {
      expect(await refusal(saveGame(deps, ctx, gameOf({ opponent: "Nope" })))).toMatchObject({ code: "forbidden", status: 403 });
      expect(await refusal(saveGame(deps, ctx, gameOf({ id, opponent: "Nope" })))).toMatchObject({ code: "forbidden" });
      expect(await refusal(deleteGame(deps, ctx, id))).toMatchObject({ code: "forbidden" });
    }
    // Staff of another team presented with our team id is no staff of ours either.
    const forged = { ...t.ctx, userId: t.ids.otherStaff };
    expect(await refusal(saveGame(deps, forged, gameOf()))).toMatchObject({ code: "forbidden" });
    expect(await count("team_games")).toBe(1);
    expect((await getGame(deps, t.ctx, id))?.opponent).toBe("Natus Vincere");
  });

  it("aMalformedIdIsJustNotFound", async () => {
    const t = await seed();
    expect(await getGame(deps, t.ctx, "not-a-uuid")).toBeNull();
    expect(await deleteGame(deps, t.ctx, "not-a-uuid")).toBe(false);
  });
});

describe("deleteGame", () => {
  it("removesTheGameItsMapsAndLines", async () => {
    const t = await seed();
    const id = await saveGame(deps, t.ctx, gameOf({ maps: [mapOf(), mapOf({ map: "ancient" })] }));
    const keep = await saveGame(deps, t.ctx, gameOf());
    expect(await deleteGame(deps, t.ctx, id)).toBe(true);
    expect(await getGame(deps, t.ctx, id)).toBeNull();
    expect(await deleteGame(deps, t.ctx, id)).toBe(false);
    expect(await count("team_games")).toBe(1);
    expect(await count("team_game_maps")).toBe(1);
    expect(await count("team_game_rows")).toBe(1);
    expect(await getGame(deps, t.ctx, keep)).not.toBeNull();
  });

  it("goesWithTheTeam", async () => {
    const t = await seed();
    await saveGame(deps, t.ctx, gameOf());
    await deps.sql`update users set team_id = null, role = 'soloPlayer' where team_id = ${t.ids.team}`;
    await deps.sql`update teams set owner_id = null where id = ${t.ids.team}`;
    await deps.sql`delete from teams where id = ${t.ids.team}`;
    expect(await count("team_games")).toBe(0);
    expect(await count("team_game_rows")).toBe(0);
  });
});

describe("listGames", () => {
  async function seedList(t: Awaited<ReturnType<typeof seed>>) {
    const early = await saveGame(deps, t.ctx, gameOf({ playedOn: "2026-10-01", kind: "pracc", maps: [mapOf({ rows: [line({ nickname: undefined, playerId: t.ids.vega })] })] }));
    const mid = await saveGame(deps, t.ctx, gameOf({ playedOn: "2026-10-10", kind: "tournament", maps: [mapOf({ ourScore: 5, theirScore: 13, rows: [line({ nickname: undefined, playerId: t.ids.nova }), line({ nickname: "ringer" })] })] }));
    const lateA = await saveGame(deps, t.ctx, gameOf({ playedOn: "2026-10-20", kind: "pracc", maps: [mapOf({ rows: [line({ nickname: undefined, playerId: t.ids.vega }), line({ nickname: undefined, playerId: t.ids.nova })] })] }));
    deps.advance(1000);
    const lateB = await saveGame(deps, t.ctx, gameOf({ playedOn: "2026-10-20", kind: "pracc" }));
    await saveGame(deps, t.otherCtx, gameOf({ playedOn: "2026-10-15" }));
    return { early, mid, lateA, lateB };
  }

  it("filtersByPeriodInclusiveNewestFirst", async () => {
    const t = await seed();
    const ids = await seedList(t);
    const all = await listGames(deps, t.ctx, { from: "2026-10-01", to: "2026-10-20" });
    expect(all.map((g) => g.id)).toEqual([ids.lateB, ids.lateA, ids.mid, ids.early]);
    const inner = await listGames(deps, t.ctx, { from: "2026-10-02", to: "2026-10-19" });
    expect(inner.map((g) => g.id)).toEqual([ids.mid]);
    expect(await listGames(deps, t.ctx, { from: "2026-11-01", to: "2026-11-30" })).toEqual([]);
  });

  it("filtersByKind", async () => {
    const t = await seed();
    const ids = await seedList(t);
    const range = { from: "2026-01-01", to: "2026-12-31" };
    expect((await listGames(deps, t.ctx, { ...range, kind: "tournament" })).map((g) => g.id)).toEqual([ids.mid]);
    expect(await listGames(deps, t.ctx, { ...range, kind: "pracc" })).toHaveLength(3);
  });

  it("filtersByPlayerAndTotalsCoverThatPlayerOnly", async () => {
    const t = await seed();
    const ids = await seedList(t);
    const range = { from: "2026-01-01", to: "2026-12-31" };
    const vega = await listGames(deps, t.ctx, { ...range, playerId: t.ids.vega });
    expect(vega.map((g) => g.id)).toEqual([ids.lateA, ids.early]);
    const [late] = vega;
    expect(late.totals).toMatchObject({ maps: 1, lines: 1, kills: 20, mapsWon: 1 });
    // The rows of the others are still part of the game.
    expect(late.maps[0].rows).toHaveLength(2);
    const standIn = await listGames(deps, t.ctx, { ...range, playerId: t.ids.ghost });
    expect(standIn).toEqual([]);
  });

  it("totalsCoverTheWholeLineupWithoutAPlayerFilter", async () => {
    const t = await seed();
    const ids = await seedList(t);
    const [mid] = await listGames(deps, t.ctx, { from: "2026-10-10", to: "2026-10-10", kind: "tournament" });
    expect(mid.id).toBe(ids.mid);
    expect(mid.totals).toMatchObject({ maps: 1, mapsLost: 1, lines: 2, kills: 40, rounds: 18 });
  });

  it("refusesAMalformedPeriod", async () => {
    const t = await seed();
    expect(await refusal(listGames(deps, t.ctx, { from: "yesterday", to: "2026-10-20" }))).toMatchObject({ code: "validation_failed" });
    expect(await refusal(listGames(deps, t.ctx, { from: "2026-10-20", to: "2026-13-40" }))).toMatchObject({ code: "validation_failed" });
  });
});
