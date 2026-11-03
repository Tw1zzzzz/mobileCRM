import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { handle } from "../lib/http";
import {
  deleteTeamLineup, getTeamLineups, putTeamLineup, saveStaffLineup, setTeamLineupFavorite,
} from "../lib/staff/lineups";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";
import { addMember, bearer, jsonRequest } from "./team-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const BASE = "http://localhost/v1/lineups";
const L1 = "a1111111-1111-4111-8111-111111111111";
const L2 = "a2222222-2222-4222-8222-222222222222";
const L3 = "a3333333-3333-4333-8333-333333333333";

/** What the Android app sends: the API's snake_case keys and upper-case UUIDs. */
const appBody = (over: Record<string, unknown> = {}) => ({
  id: L1.toUpperCase(), title: "Смок в окно", map: "mirage", side: "t", type: "smoke", details: "Jump-throw",
  video_url: "https://example.com/v", author: "Coach", created_at: "2026-10-26T12:00:00Z", visibility: "team", is_favorite: false,
  variations: [{ id: "B1111111-1111-4111-8111-111111111111", position: "T-спавн", technique: "runJump", aim: "Антенна", video_url: "https://example.com/v2" }],
  ...over,
});

const list = (token?: string) => handle(() => getTeamLineups(jsonRequest(BASE, "GET", token), deps));
const post = (token: string | undefined, payload: unknown) => handle(() => putTeamLineup(jsonRequest(BASE, "POST", token, payload), deps));
const put = (token: string | undefined, id: string, payload: unknown) =>
  handle(() => putTeamLineup(jsonRequest(`${BASE}/${id}`, "PUT", token, payload), deps, id));
const remove = (token: string | undefined, id: string) => handle(() => deleteTeamLineup(jsonRequest(`${BASE}/${id}`, "DELETE", token), deps, id));
const favorite = (token: string | undefined, id: string, payload: unknown) =>
  handle(() => setTeamLineupFavorite(jsonRequest(`${BASE}/${id}/favorite`, "PUT", token, payload), deps, id));

interface Team extends CardTeam { staff: string; coach2: string; vega: string; nova: string; solo: string; otherStaff: string }
async function seed(): Promise<Team> {
  const t = await seedCardTeam(deps);
  const coach2 = await addMember(deps, "coach2", "staff", t.ids.team);
  return {
    ...t,
    staff: await bearer(deps, t.ids.staff), coach2: await bearer(deps, coach2), vega: await bearer(deps, t.ids.vega),
    nova: await bearer(deps, t.ids.nova), solo: await bearer(deps, t.ids.solo), otherStaff: await bearer(deps, t.ids.otherStaff),
  };
}

const errorOf = async (res: Response) => ({ status: res.status, code: (await res.json()).error.code as string });
const lineupsOf = async (token: string) => (await (await list(token)).json()).data.lineups as Record<string, any>[];

describe("access and team scoping", () => {
  it("needsAMemberOfATeam", async () => {
    const t = await seed();
    const unverified = await bearer(deps, await addMember(deps, "fresh", "teamPlayer", t.ids.team, false));
    expect((await errorOf(await list())).status).toBe(401);
    for (const token of [t.solo, unverified]) {
      expect(await errorOf(await list(token))).toEqual({ status: 403, code: "forbidden" });
      expect(await errorOf(await post(token, appBody()))).toEqual({ status: 403, code: "forbidden" });
    }
  });

  it("showsEachTeamOnlyItsOwnLineups", async () => {
    const t = await seed();
    expect((await post(t.staff, appBody())).status).toBe(201);
    expect((await lineupsOf(t.vega)).map((l) => l.id)).toEqual([L1]);
    expect(await lineupsOf(t.otherStaff)).toEqual([]);
  });

  it("otherTeamsCannotTouchALineup", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect(await errorOf(await put(t.otherStaff, L1, appBody({ title: "Мой" })))).toEqual({ status: 403, code: "forbidden" });
    expect(await errorOf(await post(t.otherStaff, appBody({ title: "Перехват" })))).toEqual({ status: 403, code: "forbidden" });
    expect(await errorOf(await remove(t.otherStaff, L1))).toEqual({ status: 404, code: "not_found" });
    expect(await errorOf(await favorite(t.otherStaff, L1, { isFavorite: true }))).toEqual({ status: 404, code: "not_found" });
    const [row] = await deps.sql<{ title: string }[]>`select title from staff_lineups`;
    expect(row.title).toBe("Смок в окно");
  });
});

describe("who may edit", () => {
  it("aPlayerEditsAndDeletesOnlyTheirOwn", async () => {
    const t = await seed();
    expect((await post(t.vega, appBody())).status).toBe(201);
    expect(await errorOf(await put(t.nova, L1, appBody({ title: "Чужое" })))).toEqual({ status: 403, code: "forbidden" });
    expect(await errorOf(await remove(t.nova, L1))).toEqual({ status: 403, code: "forbidden" });
    expect((await put(t.vega, L1, appBody({ title: "Своё" }))).status).toBe(200);
    expect((await lineupsOf(t.nova))[0].title).toBe("Своё");
    expect((await remove(t.vega, L1)).status).toBe(200);
    expect(await lineupsOf(t.nova)).toEqual([]);
  });

  it("staffEditsAndDeletesEverything", async () => {
    const t = await seed();
    await post(t.vega, appBody());
    expect((await put(t.staff, L1, appBody({ title: "Правка стаффа" }))).status).toBe(200);
    expect((await lineupsOf(t.vega))[0].title).toBe("Правка стаффа");
    // the author stays the player
    expect((await lineupsOf(t.vega))[0].author).toBe("vega");
    expect((await remove(t.coach2, L1)).status).toBe(200);
    expect(await errorOf(await remove(t.staff, L1))).toEqual({ status: 404, code: "not_found" });
  });

  it("checksTheAuthorAfterTheRowIsLocked", async () => {
    const t = await seed();
    await post(t.vega, appBody());
    const novaId = (await deps.sql<{ id: string }[]>`select id from users where nickname = 'nova'`)[0].id;
    // Another request takes the lineup away from vega and commits only after vega's edit has started.
    let release!: () => void;
    const held = new Promise<void>((resolve) => { release = resolve; });
    let locked!: () => void;
    const lockTaken = new Promise<void>((resolve) => { locked = resolve; });
    const other = deps.sql.begin(async (tx) => {
      await tx`select id from staff_lineups where id = ${L1} for update`;
      await tx`update staff_lineups set created_by = ${novaId} where id = ${L1}`;
      locked();
      await held;
    });
    await lockTaken;
    const edit = put(t.vega, L1, appBody({ title: "Поздняя правка" }));
    await new Promise((resolve) => setTimeout(resolve, 300));
    release();
    await other;
    expect(await errorOf(await edit)).toEqual({ status: 403, code: "forbidden" });
    const [row] = await deps.sql<{ title: string }[]>`select title from staff_lineups`;
    expect(row.title).toBe("Смок в окно");
  });

  it("deleteChecksTheAuthorAfterTheRowIsLocked", async () => {
    const t = await seed();
    await post(t.vega, appBody());
    const novaId = (await deps.sql<{ id: string }[]>`select id from users where nickname = 'nova'`)[0].id;
    let release!: () => void;
    const held = new Promise<void>((resolve) => { release = resolve; });
    let locked!: () => void;
    const lockTaken = new Promise<void>((resolve) => { locked = resolve; });
    const other = deps.sql.begin(async (tx) => {
      await tx`select id from staff_lineups where id = ${L1} for update`;
      await tx`update staff_lineups set created_by = ${novaId} where id = ${L1}`;
      locked();
      await held;
    });
    await lockTaken;
    const del = remove(t.vega, L1);
    await new Promise((resolve) => setTimeout(resolve, 300));
    release();
    await other;
    expect(await errorOf(await del)).toEqual({ status: 403, code: "forbidden" });
    expect((await deps.sql`select 1 from staff_lineups`).length).toBe(1);
  });
});

describe("the app's wire format", () => {
  it("storesWhatTheAppSendsAndAnswersInSnakeCase", async () => {
    const t = await seed();
    const res = await post(t.staff, appBody({ is_favorite: true }));
    expect(res.status).toBe(201);
    const saved = (await res.json()).data.lineup;
    expect(saved).toMatchObject({
      id: L1, title: "Смок в окно", map: "mirage", side: "t", type: "smoke", details: "Jump-throw", video_url: "https://example.com/v",
      author: "Alpha-staff", visibility: "team", is_favorite: true,
    });
    expect(saved.created_at).toBe(deps.now().toISOString());
    expect(saved.variations).toEqual([
      { id: "b1111111-1111-4111-8111-111111111111", position: "T-спавн", technique: "runJump", aim: "Антенна", video_url: "https://example.com/v2" },
    ]);
    expect(Object.keys(saved).sort()).toEqual(
      ["author", "created_at", "details", "id", "is_favorite", "map", "side", "title", "type", "variations", "video_url", "visibility"],
    );
    expect(await lineupsOf(t.vega)).toEqual([{ ...saved, is_favorite: false }]);
  });

  it("anUpperCaseBodyIdMatchesTheLowerCasePath", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect((await put(t.staff, L1, appBody({ title: "Из PUT" }))).status).toBe(200);
    expect((await lineupsOf(t.staff))[0].title).toBe("Из PUT");
    expect((await deps.sql`select 1 from staff_lineups`).length).toBe(1);
  });

  it("stillReadsTheCamelCaseKeysOfEarlierClients", async () => {
    const t = await seed();
    const body = {
      id: L2, title: "Старый клиент", map: "nuke", side: "ct", type: "flash", details: "", videoURL: "https://example.com/old",
      visibility: "team", isFavorite: true,
      variations: [{ position: "A", technique: "jump", aim: "B", videoURL: "https://example.com/vo" }],
    };
    expect((await post(t.staff, body)).status).toBe(201);
    const saved = (await lineupsOf(t.staff))[0];
    expect(saved).toMatchObject({ video_url: "https://example.com/old", is_favorite: true });
    expect(saved.variations[0]).toMatchObject({ position: "A", video_url: "https://example.com/vo" });
  });

  it("readsVariationsStoredByTheEarlierServer", async () => {
    const t = await seed();
    await post(t.staff, appBody({ variations: [] }));
    await deps.sql`update staff_lineups set variations = ${deps.sql.json([{ id: "b1111111-1111-4111-8111-111111111111", position: "P", technique: "run", aim: "A", videoURL: "https://example.com/e" }])}`;
    expect((await lineupsOf(t.staff))[0].variations).toEqual([
      { id: "b1111111-1111-4111-8111-111111111111", position: "P", technique: "run", aim: "A", video_url: "https://example.com/e" },
    ]);
  });
});

describe("notes", () => {
  it("acceptsANoteWithOrWithoutSideAndVariations", async () => {
    const t = await seed();
    expect((await post(t.staff, appBody({ id: L2, type: "note", title: "Разбор Train", variations: [] }))).status).toBe(201);
    expect((await post(t.staff, appBody({ id: L3, type: "note", title: "С вариациями" }))).status).toBe(201);
    const types = (await lineupsOf(t.vega)).map((l) => l.type);
    expect(types).toEqual(["note", "note"]);
    expect((await put(t.staff, L2, appBody({ id: L2, type: "note", title: "Правка заметки" }))).status).toBe(200);
  });

  it("theWebsiteSavesANote", async () => {
    const t = await seed();
    const form = new FormData();
    for (const [k, v] of Object.entries({ title: "Заметка с сайта", map: "train", side: "ct", type: "note", details: "Текст", videoUrl: "" })) form.set(k, v);
    expect(await saveStaffLineup(deps, t.ctx, form)).toMatchObject({ created: true });
    expect((await lineupsOf(t.vega))[0]).toMatchObject({ type: "note", title: "Заметка с сайта" });
  });
});

describe("favorites", () => {
  it("areKeptPerUser", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect(await (await favorite(t.staff, L1, { isFavorite: true })).json()).toMatchObject({ data: { isFavorite: true } });
    expect((await lineupsOf(t.staff))[0].is_favorite).toBe(true);
    expect((await lineupsOf(t.coach2))[0].is_favorite).toBe(false);
    expect((await lineupsOf(t.vega))[0].is_favorite).toBe(false);
    await favorite(t.coach2, L1, { isFavorite: true });
    await favorite(t.staff, L1, { isFavorite: false });
    expect((await lineupsOf(t.staff))[0].is_favorite).toBe(false);
    expect((await lineupsOf(t.coach2))[0].is_favorite).toBe(true);
  });

  it("travelWithAnEditByThatUserOnly", async () => {
    const t = await seed();
    await post(t.staff, appBody({ is_favorite: true }));
    await put(t.coach2, L1, appBody({ is_favorite: false, title: "Правка" }));
    expect((await lineupsOf(t.staff))[0].is_favorite).toBe(true);
    expect((await lineupsOf(t.coach2))[0].is_favorite).toBe(false);
  });

  it("validatesTheFavoriteRequest", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect(await errorOf(await favorite(t.staff, L1, { isFavorite: "yes" }))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await favorite(t.staff, "nope", { isFavorite: true }))).toEqual({ status: 404, code: "not_found" });
    expect(await errorOf(await favorite(t.staff, L2, { isFavorite: true }))).toEqual({ status: 404, code: "not_found" });
  });

  it("acceptsTheSnakeCaseFlagToo", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect((await favorite(t.staff, L1, { is_favorite: true })).status).toBe(200);
    expect((await lineupsOf(t.staff))[0].is_favorite).toBe(true);
  });
});

describe("validation", () => {
  const invalid = async (t: Team, over: Record<string, unknown>) =>
    expect(await errorOf(await post(t.staff, appBody(over)))).toEqual({ status: 422, code: "validation_failed" });

  it("checksTheEnums", async () => {
    const t = await seed();
    await invalid(t, { map: "cache" });
    await invalid(t, { side: "both" });
    await invalid(t, { type: "wallbang" });
    await invalid(t, { variations: [{ position: "p", technique: "teleport", aim: "a" }] });
    await invalid(t, { visibility: "personal" });
    for (const map of ["mirage", "inferno", "nuke", "ancient", "anubis", "dust2", "train"]) {
      expect((await post(t.staff, appBody({ id: crypto.randomUUID(), map }))).status).toBe(201);
    }
    for (const type of ["smoke", "flash", "molotov", "grenade", "script", "note"]) {
      expect((await post(t.staff, appBody({ id: crypto.randomUUID(), type }))).status).toBe(201);
    }
    for (const technique of ["standard", "jump", "run", "runJump", "walk", "rightClick"]) {
      expect((await post(t.staff, appBody({ id: crypto.randomUUID(), variations: [{ position: "p", technique, aim: "a" }] }))).status).toBe(201);
    }
  });

  it("checksTextsAndLinks", async () => {
    const t = await seed();
    await invalid(t, { title: "  " });
    await invalid(t, { title: "я".repeat(121) });
    await invalid(t, { details: "я".repeat(4001) });
    await invalid(t, { video_url: "javascript:alert(1)" });
    await invalid(t, { video_url: "https://u:p@example.com/" });
    await invalid(t, { variations: [{ position: "p", technique: "jump", aim: "a", video_url: "ftp://example.com" }] });
    await invalid(t, { variations: [{ position: "я".repeat(241), technique: "jump", aim: "a" }] });
    await invalid(t, { variations: Array.from({ length: 21 }, () => ({ position: "p", technique: "jump", aim: "a" })) });
    await invalid(t, { id: "not-a-uuid" });
  });

  it("checksThePathAgainstTheBody", async () => {
    const t = await seed();
    await post(t.staff, appBody());
    expect(await errorOf(await put(t.staff, L1, appBody({ id: L2 })))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await put(t.staff, "nope", appBody()))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await remove(t.staff, "nope"))).toEqual({ status: 404, code: "not_found" });
  });

  it("refusesHugeBodies", async () => {
    const t = await seed();
    expect(await errorOf(await post(t.staff, appBody({ details: "я".repeat(70_000) })))).toEqual({ status: 413, code: "payload_too_large" });
  });
});
