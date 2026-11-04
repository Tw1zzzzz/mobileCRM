import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { requireStaff } from "../lib/staff/access";
import { GAME_ERRORS, GAME_NOTICES, saveGameCore } from "../lib/staff/game-forms";
import { getGame } from "../lib/staff/games";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam } from "./staff-card-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const ID = "f6666666-6666-4666-8666-666666666666";

const game = (over: object = {}) => ({
  id: ID, kind: "pracc", playedOn: "2026-10-25", opponent: null,
  maps: [{ map: "mirage", ourScore: 13, theirScore: 7, pistol1: "won", pistol2: "lost", rows: [{ nickname: "ringer", kills: 20, deaths: 10, assists: 4, adr: 82.5, fk: null }] }],
  ...over,
});

const gameCount = async () => (await deps.sql`select count(*)::int as n from team_games`)[0].n as number;

describe("saving a game from the form", () => {
  it("savesTheGameOfTheSessionsTeam", async () => {
    const t = await seedCardTeam(deps);
    expect(await saveGameCore(deps, t.ctx, game())).toEqual({ status: "done", message: GAME_NOTICES.saved });
    const saved = await getGame(deps, t.ctx, ID);
    expect(saved).toMatchObject({ kind: "pracc", playedOn: "2026-10-25", maps: [{ map: "mirage", ourScore: 13 }] });
    // Sending the same id again (a second press after a lost answer) replaces the game.
    expect(await saveGameCore(deps, t.ctx, game({ opponent: "Natus Vincere" }))).toMatchObject({ status: "done" });
    expect((await getGame(deps, t.ctx, ID))?.opponent).toBe("Natus Vincere");
    expect(await gameCount()).toBe(1);
  });

  it("garbageIsRefusedWithATextAndNothingIsWritten", async () => {
    const t = await seedCardTeam(deps);
    for (const junk of [null, "x", 5, [], {}, game({ kind: "scrim" }), game({ maps: [] }), game({ playedOn: "yesterday" })]) {
      expect(await saveGameCore(deps, t.ctx, junk)).toEqual({ status: "error", message: GAME_ERRORS.invalid });
    }
    expect(await gameCount()).toBe(0);
  });

  it("anotherTeamsGameOrPlayerIsRefused", async () => {
    const t = await seedCardTeam(deps);
    const other = await requireStaff(deps, t.ids.otherStaff);
    await saveGameCore(deps, other, game());
    // Our staff cannot overwrite their game through its id...
    expect(await saveGameCore(deps, t.ctx, game({ opponent: "Hijack" }))).toEqual({ status: "error", message: GAME_ERRORS.notFound });
    expect((await getGame(deps, other, ID))?.opponent).toBeNull();
    // ...nor put their player on ours.
    const foreign = game({ id: undefined, maps: [{ ...game().maps[0], rows: [{ playerId: t.ids.outsider, kills: 1, deaths: 1, assists: 1, adr: 1 }] }] });
    expect(await saveGameCore(deps, t.ctx, foreign)).toEqual({ status: "error", message: GAME_ERRORS.invalid });
    expect(await gameCount()).toBe(1);
  });

  it("aPlayerMayNotSave", async () => {
    const t = await seedCardTeam(deps);
    const result = await saveGameCore(deps, { ...t.ctx, userId: t.ids.vega }, game());
    expect(result).toEqual({ status: "error", message: GAME_ERRORS.failed });
    expect(await gameCount()).toBe(0);
  });
});
