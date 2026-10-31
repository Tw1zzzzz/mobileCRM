import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { issueSession, revokeRefresh } from "../lib/auth/tokens";
import { staffMealPhoto } from "../lib/staff/photos";
import { removeMember } from "../lib/team/members";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();

// The route runs against the test database with a cookie we control.
const session = vi.hoisted(() => ({ token: undefined as string | undefined }));
vi.mock("@/lib/deps", () => ({ defaultDeps: () => deps }));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name === "pulse_staff" && session.token ? { value: session.token } : undefined),
    set: () => undefined,
    delete: () => undefined,
  }),
  headers: async () => new Headers(),
}));

beforeEach(async () => {
  session.token = undefined;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
const OTHER_JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xdb, 0x01, 0x02, 0x03, 0xff, 0xd9]);
const MEAL = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const OTHER_MEAL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const UNKNOWN_MEAL = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

async function storePhoto(userId: string, mealId: string, jpeg: Uint8Array): Promise<void> {
  await deps.sql`
    insert into meal_photos (user_id, meal_id, jpeg, updated_at)
    values (${userId}, ${mealId}, ${jpeg}, ${deps.now()})`;
}

async function seed(): Promise<CardTeam> {
  const t = await seedCardTeam(deps);
  await storePhoto(t.ids.vega, MEAL, JPEG);
  await storePhoto(t.ids.outsider, OTHER_MEAL, OTHER_JPEG);
  await storePhoto(t.ids.solo, OTHER_MEAL, OTHER_JPEG);
  session.token = (await issueSession(deps, t.ids.staff)).refresh_token;
  return t;
}

async function get(playerId: string, mealId: string): Promise<Response> {
  const { GET } = await import("../app/staff/(panel)/photos/[playerId]/[mealId]/route");
  return GET(new Request(`http://localhost/staff/photos/${playerId}/${mealId}`), {
    params: Promise.resolve({ playerId, mealId }),
  });
}

async function bytes(res: Response): Promise<Uint8Array> {
  return new Uint8Array(await res.arrayBuffer());
}

/** Everything a caller can observe of a 404, to compare two of them. */
async function observed(res: Response) {
  return {
    status: res.status,
    body: Array.from(await bytes(res)),
    type: res.headers.get("content-type"),
    cache: res.headers.get("cache-control"),
  };
}

describe("meal photo route", () => {
  it("servesATeamPlayersPhotoWithTheHeaders", async () => {
    const t = await seed();
    const res = await get(t.ids.vega, MEAL);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("image/jpeg");
    expect(res.headers.get("cache-control")).toBe("private, max-age=3600");
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(await bytes(res)).toEqual(JPEG);
  });

  it("photoRouteChecksRoster", async () => {
    const t = await seed();
    // another team's player and a solo player have photos on the server; staff never get the bytes
    for (const [player, meal] of [[t.ids.outsider, OTHER_MEAL], [t.ids.solo, OTHER_MEAL]]) {
      const res = await get(player, meal);
      expect(res.status, player).toBe(404);
      expect(await bytes(res)).toHaveLength(0);
    }
    // staff members are not players either
    expect((await get(t.ids.staff, MEAL)).status).toBe(404);
    expect((await get(t.ids.otherStaff, MEAL)).status).toBe(404);
    // a player's photo under someone else's id
    expect((await get(t.ids.nova, MEAL)).status).toBe(404);
    // a removed member is no longer on the roster
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.vega);
    const removed = await get(t.ids.vega, MEAL);
    expect(removed.status).toBe(404);
    expect(await bytes(removed)).toHaveLength(0);
  });

  it("noSuchPlayerAndNoSuchPhotoLookTheSame", async () => {
    const t = await seed();
    const foreign = await observed(await get(t.ids.outsider, OTHER_MEAL));
    const noPhoto = await observed(await get(t.ids.vega, UNKNOWN_MEAL));
    const noPlayer = await observed(await get("dddddddd-dddd-4ddd-8ddd-dddddddddddd", MEAL));
    expect(foreign.status).toBe(404);
    expect(foreign.body).toEqual([]);
    expect(noPhoto).toEqual(foreign);
    expect(noPlayer).toEqual(foreign);
  });

  it("photoRouteRejectsBadIds", async () => {
    const t = await seed();
    const bad = ["", "x", "not-a-guid", "../../etc/passwd", "1; drop table meal_photos", "%00", "a".repeat(5000)];
    for (const id of bad) {
      for (const [player, meal] of [[id, MEAL], [t.ids.vega, id]]) {
        const res = await get(player, meal);
        expect(res.status, `${player.slice(0, 20)} / ${meal.slice(0, 20)}`).toBe(404);
        expect(await bytes(res)).toHaveLength(0);
      }
    }
    // the store is untouched
    const [{ count }] = await deps.sql<{ count: string }[]>`select count(*) from meal_photos`;
    expect(count).toBe("3");
  });

  it("anUpperCaseMealIdIsTheSameMeal", async () => {
    const t = await seed();
    const res = await get(t.ids.vega, MEAL.toUpperCase());
    expect(res.status).toBe(200);
    expect(await bytes(res)).toEqual(JPEG);
  });

  it("visitorsWithoutAStaffSessionGetAPlainStatusNotARedirect", async () => {
    const t = await seed();
    // nobody signed in
    session.token = undefined;
    const anonymous = await get(t.ids.vega, MEAL);
    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get("location")).toBeNull();
    expect(await bytes(anonymous)).toHaveLength(0);
    // a player of that very team
    session.token = (await issueSession(deps, t.ids.vega)).refresh_token;
    const player = await get(t.ids.vega, MEAL);
    expect(player.status).toBe(403);
    expect(await bytes(player)).toHaveLength(0);
    // a signed-out (revoked) staff session
    const staffToken = (await issueSession(deps, t.ids.staff)).refresh_token;
    session.token = staffToken;
    expect((await get(t.ids.vega, MEAL)).status).toBe(200);
    await revokeRefresh(deps, staffToken);
    expect((await get(t.ids.vega, MEAL)).status).toBe(401);
  });

  it("anotherTeamsStaffCannotSeeThePhoto", async () => {
    const t = await seed();
    session.token = (await issueSession(deps, t.ids.otherStaff)).refresh_token;
    const res = await get(t.ids.vega, MEAL);
    expect(res.status).toBe(404);
    expect(await bytes(res)).toHaveLength(0);
  });
});

describe("staffMealPhoto", () => {
  it("returnsTheBytesForARosterPlayerAndNullOtherwise", async () => {
    const t = await seed();
    expect(await staffMealPhoto(deps, t.ctx, t.ids.vega, MEAL)).toEqual(JPEG);
    expect(await staffMealPhoto(deps, t.ctx, t.ids.vega, UNKNOWN_MEAL)).toBeNull();
    expect(await staffMealPhoto(deps, t.ctx, t.ids.outsider, OTHER_MEAL)).toBeNull();
    expect(await staffMealPhoto(deps, t.ctx, "nope", MEAL)).toBeNull();
    expect(await staffMealPhoto(deps, t.ctx, t.ids.vega, "nope")).toBeNull();
  });
});
