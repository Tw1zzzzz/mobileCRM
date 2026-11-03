import { readFileSync } from "node:fs";
import { join } from "node:path";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import {
  createTeamCalendarEvent, deleteTeamCalendarEvent, getTeamCalendar, updateTeamCalendarEvent,
} from "../lib/calendar-api";
import { handle } from "../lib/http";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";
import { addMember, bearer, jsonRequest } from "./team-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const URL_BASE = "http://localhost/v1/calendar";
const E1 = "11111111-1111-4111-8111-111111111111";
const E2 = "22222222-2222-4222-8222-222222222222";
const E3 = "33333333-3333-4333-8333-333333333333";

const body = (over: Record<string, unknown> = {}) => ({
  id: E1, title: "Тренировка", details: "Разбор карты", start: "2026-10-27T10:00:00Z", end: "2026-10-27T12:00:00Z",
  links: ["https://example.com/vod"], tag: "team", ...over,
});

const list = (token?: string) => handle(() => getTeamCalendar(jsonRequest(URL_BASE, "GET", token), deps));
const create = (token: string | undefined, payload: unknown) =>
  handle(() => createTeamCalendarEvent(jsonRequest(URL_BASE, "POST", token, payload), deps));
const update = (token: string | undefined, id: string, payload: unknown) =>
  handle(() => updateTeamCalendarEvent(jsonRequest(`${URL_BASE}/${id}`, "PUT", token, payload), deps, id));
const remove = (token: string | undefined, id: string) =>
  handle(() => deleteTeamCalendarEvent(jsonRequest(`${URL_BASE}/${id}`, "DELETE", token), deps, id));

interface Team extends CardTeam { staffToken: string; vegaToken: string; novaToken: string; soloToken: string; otherStaffToken: string }
async function seed(): Promise<Team> {
  const t = await seedCardTeam(deps);
  return {
    ...t,
    staffToken: await bearer(deps, t.ids.staff), vegaToken: await bearer(deps, t.ids.vega), novaToken: await bearer(deps, t.ids.nova),
    soloToken: await bearer(deps, t.ids.solo), otherStaffToken: await bearer(deps, t.ids.otherStaff),
  };
}

async function errorOf(res: Response): Promise<{ status: number; code: string }> {
  return { status: res.status, code: (await res.json()).error.code };
}

describe("team calendar access", () => {
  it("needsABearerToken", async () => {
    await seed();
    expect((await errorOf(await list())).status).toBe(401);
    expect((await errorOf(await create(undefined, body()))).status).toBe(401);
  });

  it("nonMembersAreForbidden", async () => {
    const t = await seed();
    const unverified = await addMember(deps, "fresh", "teamPlayer", t.ids.team, false);
    const teamless = await addMember(deps, "lost", "teamPlayer", null);
    for (const token of [t.soloToken, await bearer(deps, unverified), await bearer(deps, teamless)]) {
      expect(await errorOf(await list(token))).toEqual({ status: 403, code: "forbidden" });
      expect(await errorOf(await create(token, body()))).toEqual({ status: 403, code: "forbidden" });
    }
  });

  it("playersReadOnlyTeamAndOwnPlayerEvents", async () => {
    const t = await seed();
    expect((await create(t.staffToken, body({ id: E1, title: "Команда", tag: "team" }))).status).toBe(200);
    expect((await create(t.staffToken, body({ id: E2, title: "Для vega", tag: "player", player_id: t.ids.vega }))).status).toBe(200);
    expect((await create(t.staffToken, body({ id: E3, title: "Для nova", tag: "player", player_id: t.ids.nova }))).status).toBe(200);

    const titles = async (token: string) => ((await (await list(token)).json()).data.events as { title: string }[]).map((e) => e.title).sort();
    expect(await titles(t.staffToken)).toEqual(["Для nova", "Для vega", "Команда"]);
    expect(await titles(t.vegaToken)).toEqual(["Для vega", "Команда"]);
    expect(await titles(t.novaToken)).toEqual(["Для nova", "Команда"]);

    const staffView = (await (await list(t.staffToken)).json()).data;
    expect(staffView.team_id).toBe(t.ids.team);
    expect((staffView.players as { nickname: string }[]).map((p) => p.nickname).sort()).toEqual(["ghost", "nova", "vega"]);
    expect((await (await list(t.vegaToken)).json()).data.players).toEqual([]);
  });

  it("otherTeamsEventsAreInvisibleAndUntouchable", async () => {
    const t = await seed();
    expect((await create(t.otherStaffToken, body({ id: E1, title: "Чужое" }))).status).toBe(200);
    expect(((await (await list(t.staffToken)).json()).data.events as unknown[]).length).toBe(0);
    expect(await errorOf(await update(t.staffToken, E1, body({ title: "Мой" })))).toEqual({ status: 404, code: "not_found" });
    expect(await errorOf(await remove(t.staffToken, E1))).toEqual({ status: 404, code: "not_found" });
    // creating with a foreign event id must not overwrite it either
    expect(await errorOf(await create(t.staffToken, body({ id: E1, title: "Перехват" })))).toEqual({ status: 404, code: "not_found" });
    const [row] = await deps.sql<{ title: string }[]>`select title from staff_calendar_events where id = ${E1}`;
    expect(row.title).toBe("Чужое");
  });
});

describe("team calendar writes", () => {
  it("onlyStaffWritesAndDeletes", async () => {
    const t = await seed();
    expect((await create(t.staffToken, body())).status).toBe(200);
    expect(await errorOf(await create(t.vegaToken, body({ id: E2 })))).toEqual({ status: 403, code: "forbidden" });
    expect(await errorOf(await update(t.vegaToken, E1, body({ title: "Взлом" })))).toEqual({ status: 403, code: "forbidden" });
    expect(await errorOf(await remove(t.vegaToken, E1))).toEqual({ status: 403, code: "forbidden" });
    const [row] = await deps.sql<{ title: string }[]>`select title from staff_calendar_events where id = ${E1}`;
    expect(row.title).toBe("Тренировка");
    expect((await update(t.staffToken, E1, body({ title: "Новое" }))).status).toBe(200);
    expect((await remove(t.staffToken, E1)).status).toBe(200);
    expect(await errorOf(await remove(t.staffToken, E1))).toEqual({ status: 404, code: "not_found" });
  });

  it("postingTheSameIdAgainUpdatesInsteadOfDuplicating", async () => {
    const t = await seed();
    await create(t.staffToken, body());
    await create(t.staffToken, body({ title: "Повтор" }));
    const rows = await deps.sql<{ title: string }[]>`select title from staff_calendar_events`;
    expect(rows).toEqual([{ title: "Повтор" }]);
  });
});

describe("player events", () => {
  it("resolvesAPlayerById", async () => {
    const t = await seed();
    const res = await create(t.staffToken, body({ tag: "player", player_id: t.ids.vega }));
    expect(res.status).toBe(200);
    const event = (await (await list(t.staffToken)).json()).data.events[0];
    expect(event.player_id).toBe(t.ids.vega);
    expect(event.player_nickname).toBe("vega");
    expect(event.tag).toBe("player");
  });

  it("returnsNullPlayerFieldsForTeamEvents", async () => {
    const t = await seed();
    await create(t.staffToken, body());
    const event = (await (await list(t.staffToken)).json()).data.events[0];
    expect(event.player_id).toBeNull();
    expect(event.player_nickname).toBeNull();
  });

  it("resolvesAUniqueNicknameForOldApps", async () => {
    const t = await seed();
    expect((await create(t.staffToken, body({ tag: "player", player_nickname: "nova" }))).status).toBe(200);
    const event = (await (await list(t.staffToken)).json()).data.events[0];
    expect(event.player_id).toBe(t.ids.nova);
  });

  it("refusesADuplicateNicknameWithoutAnId", async () => {
    const t = await seed();
    await addMember(deps, "vega", "teamPlayer", t.ids.team);
    expect(await errorOf(await create(t.staffToken, body({ tag: "player", player_nickname: "vega" }))))
      .toEqual({ status: 422, code: "validation_failed" });
    const count = await deps.sql`select 1 from staff_calendar_events`;
    expect(count.length).toBe(0);
  });

  it("anIdPicksTheRightPlayerAmongSameNicknames", async () => {
    const t = await seed();
    const twin = await addMember(deps, "vega", "teamPlayer", t.ids.team);
    // the id wins over a nickname that would be ambiguous or belong to someone else
    expect((await create(t.staffToken, body({ tag: "player", player_id: twin, player_nickname: "nova" }))).status).toBe(200);
    const [row] = await deps.sql<{ player_id: string }[]>`select player_id from staff_calendar_events`;
    expect(row.player_id).toBe(twin);
  });

  it("refusesPlayersOutsideTheTeam", async () => {
    const t = await seed();
    const unverified = await addMember(deps, "fresh", "teamPlayer", t.ids.team, false);
    for (const id of [t.ids.outsider, t.ids.solo, t.ids.staff, unverified, "99999999-9999-4999-8999-999999999999"]) {
      expect(await errorOf(await create(t.staffToken, body({ tag: "player", player_id: id })))).toEqual({ status: 422, code: "validation_failed" });
    }
    expect(await errorOf(await create(t.staffToken, body({ tag: "player", player_nickname: "zed-outsider" })))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await create(t.staffToken, body({ tag: "player" })))).toEqual({ status: 422, code: "validation_failed" });
  });

  it("ignoresPlayerFieldsOnTeamEvents", async () => {
    const t = await seed();
    expect((await create(t.staffToken, body({ tag: "team", player_id: t.ids.vega }))).status).toBe(200);
    const [row] = await deps.sql<{ player_id: string | null }[]>`select player_id from staff_calendar_events`;
    expect(row.player_id).toBeNull();
  });
});

describe("team calendar validation", () => {
  const invalid = async (t: Team, over: Record<string, unknown>) =>
    expect(await errorOf(await create(t.staffToken, body(over)))).toEqual({ status: 422, code: "validation_failed" });

  it("checksTheTimeRange", async () => {
    const t = await seed();
    await invalid(t, { start: "2026-10-27T12:00:00Z", end: "2026-10-27T12:00:00Z" });
    await invalid(t, { start: "2026-10-27T12:00:00Z", end: "2026-10-27T11:00:00Z" });
    await invalid(t, { start: "2026-10-27T10:00:00Z", end: "2026-11-04T10:00:01Z" });
    await invalid(t, { start: "not a date" });
    expect((await create(t.staffToken, body({ id: E2, start: "2026-10-27T10:00:00Z", end: "2026-11-03T10:00:00Z" }))).status).toBe(200);
  });

  it("checksTheLinks", async () => {
    const t = await seed();
    await invalid(t, { links: ["javascript:alert(1)"] });
    await invalid(t, { links: ["ftp://example.com/x"] });
    await invalid(t, { links: ["https://user:pass@example.com/"] });
    await invalid(t, { links: ["not a url"] });
    await invalid(t, { links: Array.from({ length: 21 }, (_, i) => `https://example.com/${i}`) });
    await invalid(t, { links: ["https://example.com/" + "a".repeat(2100)] });
    await invalid(t, { links: undefined });
    expect((await create(t.staffToken, body({ id: E2, links: [] }))).status).toBe(200);
  });

  it("checksTheTitleAndDetails", async () => {
    const t = await seed();
    await invalid(t, { title: "   " });
    await invalid(t, { title: "я".repeat(121) });
    await invalid(t, { details: "я".repeat(4001) });
    await invalid(t, { tag: "personal" });
    await invalid(t, { id: "nope" });
    expect((await create(t.staffToken, body({ id: E2, title: "я".repeat(120), details: "" }))).status).toBe(200);
  });

  it("checksTheIdsOfAnUpdate", async () => {
    const t = await seed();
    await create(t.staffToken, body());
    expect(await errorOf(await update(t.staffToken, E1, body({ id: E2 })))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await update(t.staffToken, "nope", body()))).toEqual({ status: 422, code: "validation_failed" });
    expect(await errorOf(await remove(t.staffToken, "nope"))).toEqual({ status: 422, code: "validation_failed" });
  });
});

describe("migration 010 keeps events that were shared through per-user documents", () => {
  const sql = readFileSync(join(__dirname, "..", "db", "migrations", "010_staff_calendar_events.sql"), "utf8");
  const dataPart = sql.slice(sql.indexOf("-- Preserve"));
  const doc = (events: unknown[]) => events;
  const ev = (id: string, title: string, tag: unknown, extra: Record<string, unknown> = {}) => ({
    id, title, details: "", start: "2026-10-27T10:00:00Z", end: "2026-10-27T11:00:00Z", links: [], tag, ...extra,
  });

  it("copiesOldSharedEventsOnce", async () => {
    const t = await seed();
    // the same team event sits in the documents of the coach and of a player; a personal one and broken ones must not move
    await putDoc(deps, t.ids.staff, "calendar", doc([
      ev(E1, "Командное", { team: {} }, { links: ["https://example.com/a"] }),
      ev(E2, "Для nova", { player: { _0: "nova" } }),
      ev(E3, "Личное", { personal: {} }),
      ev("not-a-uuid", "Сломанное", { team: {} }),
      ev("44444444-4444-4444-8444-444444444444", "Для призрака", { player: { _0: "nobody" } }),
      ev("55555555-5555-4555-8555-555555555555", "Перевёрнутое", { team: {} }, { start: "2026-10-27T11:00:00Z", end: "2026-10-27T10:00:00Z" }),
    ]));
    await putDoc(deps, t.ids.vega, "calendar", doc([ev(E1, "Командное", { team: {} }, { links: ["https://example.com/a"] })]));
    await putDoc(deps, t.ids.outsider, "calendar", doc([ev("66666666-6666-4666-8666-666666666666", "Чужая команда", { team: {} })]));

    try {
      await deps.sql.unsafe("drop table staff_calendar_events");
      await deps.sql.unsafe(sql);
      const read = () => deps.sql<{ id: string; team_id: string; title: string; tag: string; player_id: string | null; links: string[] }[]>`
        select id, team_id, title, tag, player_id, links from staff_calendar_events order by title`;
      const rows = await read();
      expect(rows.map((r) => r.title).sort()).toEqual(["Командное", "Для nova", "Чужая команда"].sort());
      const byTitle = Object.fromEntries(rows.map((r) => [r.title, r]));
      expect(byTitle["Командное"]).toMatchObject({ id: E1, team_id: t.ids.team, tag: "team", links: ["https://example.com/a"] });
      expect(byTitle["Для nova"]).toMatchObject({ id: E2, tag: "player", player_id: t.ids.nova });

      // running the copy again (a re-deploy) changes nothing
      await deps.sql.unsafe(dataPart);
      expect(await read()).toEqual(rows);
    } finally {
      const [{ exists }] = await deps.sql<{ exists: boolean }[]>`select to_regclass('public.staff_calendar_events') is not null as exists`;
      if (!exists) await deps.sql.unsafe(sql);
    }
  });
});
