import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { issueSession } from "../lib/auth/tokens";
import { handle } from "../lib/http";
import { getStaffMealPhoto, getStaffPlayerDocuments, getStaffTeam } from "../lib/staff/api";
import { resetDb, testDeps } from "./helpers";
import { putDoc, seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();

beforeEach(async () => {
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
const MEAL = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const OTHER_MEAL = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

async function tokenOf(userId: string): Promise<string> {
  return (await issueSession(deps, userId)).access_token;
}

function request(path: string, token?: string): Request {
  return new Request(`http://localhost/v1/staff/${path}`, {
    headers: token ? { authorization: `Bearer ${token}` } : {},
  });
}

const team = async (token?: string) => handle(() => getStaffTeam(request("team", token), deps));
const documents = async (playerId: string, collections: string | null, token?: string) =>
  handle(() =>
    getStaffPlayerDocuments(
      request(`players/${playerId}/documents${collections === null ? "" : `?collections=${collections}`}`, token),
      deps,
      playerId,
    ),
  );
const photo = async (playerId: string, mealId: string, token?: string) =>
  handle(() => getStaffMealPhoto(request(`players/${playerId}/photos/${mealId}`, token), deps, playerId, mealId));

async function storePhoto(userId: string, mealId: string): Promise<void> {
  await deps.sql`
    insert into meal_photos (user_id, meal_id, jpeg, updated_at) values (${userId}, ${mealId}, ${JPEG}, ${deps.now()})`;
}

async function seed(): Promise<CardTeam> {
  const t = await seedCardTeam(deps);
  await putDoc(deps, t.ids.vega, "lineups", [
    { id: "l1", title: "Smoke mirage", visibility: "team" },
    { id: "l2", title: "Private diary note", visibility: "personal" },
  ]);
  await putDoc(deps, t.ids.vega, "player-survey", {
    survey: { goals: ["rank"], role: "entry", level: "faceit 10" },
    mindset: [{ id: "r1", date: "2026-10-20T08:00:00Z", answers: { q1: 5, q2: 1 } }],
    onboardingDismissed: true,
  });
  await putDoc(deps, t.ids.vega, "body-profile", { birthDate: "2000-01-01", heightCm: 180, weightKg: 75 });
  await putDoc(deps, t.ids.outsider, "sleep", { sessions: [{ id: "x", start: "2026-10-25T20:00:00Z", end: "2026-10-26T04:00:00Z" }] });
  return t;
}

async function exhaust(bucket: string, userId: string, count: number): Promise<void> {
  const windowStart = new Date(Math.floor(deps.now().getTime() / 60_000) * 60_000);
  await deps.sql`insert into rate_limits (key, window_start, count) values (${`${bucket}:${userId}`}, ${windowStart}, ${count})`;
}

describe("GET /v1/staff/team", () => {
  it("teamListsOnlyTeamPlayers", async () => {
    const t = await seed();
    const res = await team(await tokenOf(t.ids.staff));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.data.team).toEqual({ name: "Alpha", invite_code: "PALPHA" });
    const players = body.data.players as { id: string; nickname: string; last_activity: string | null }[];
    expect(players.map((p) => p.nickname).sort()).toEqual(["ghost", "nova", "vega"]);
    expect(players.find((p) => p.nickname === "ghost")!.last_activity).toBeNull();
    expect(typeof players.find((p) => p.nickname === "vega")!.last_activity).toBe("string");
    expect(Object.keys(players[0]).sort()).toEqual(["id", "last_activity", "nickname"]);
    expect(JSON.stringify(body)).not.toContain(t.ids.outsider);
    expect(JSON.stringify(body)).not.toContain(t.ids.solo);
  });

  it("needsBearer", async () => {
    await seed();
    const res = await team();
    expect(res.status).toBe(401);
    expect((await res.json()).error.code).toBe("unauthorized");
  });

  it("nonStaffIsForbidden", async () => {
    const t = await seed();
    for (const id of [t.ids.vega, t.ids.solo]) {
      const res = await team(await tokenOf(id));
      expect(res.status).toBe(403);
      expect((await res.json()).error.code).toBe("forbidden");
    }
    const docs = await documents(t.ids.vega, "sleep", await tokenOf(t.ids.vega));
    expect(docs.status).toBe(403);
    const pic = await photo(t.ids.vega, MEAL, await tokenOf(t.ids.vega));
    expect(pic.status).toBe(403);
  });
});

describe("GET /v1/staff/players/{id}/documents", () => {
  it("documentsAreFiltered", async () => {
    const t = await seed();
    const res = await documents(t.ids.vega, "sleep,lineups,player-survey,nutrition", await tokenOf(t.ids.staff));
    expect(res.status).toBe(200);
    const { documents: docs } = (await res.json()).data;
    expect(Object.keys(docs).sort()).toEqual(["lineups", "player-survey", "sleep"]);
    expect(docs.lineups).toEqual([{ id: "l1", title: "Smoke mirage", visibility: "team" }]);
    expect(docs["player-survey"].survey).toBeUndefined();
    expect(docs["player-survey"].mindset).toHaveLength(1);
    expect(docs["player-survey"].mindset[0].answers).toBeUndefined();
    expect(docs["player-survey"].mindset[0].scores).toBeDefined();
    expect(docs.sleep.sessions).toHaveLength(2);
  });

  it("bodyProfileRefused", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    const res = await documents(t.ids.vega, "sleep,body-profile", token);
    expect(res.status).toBe(403);
    const body = await res.json();
    expect(body.error.code).toBe("forbidden");
    expect(JSON.stringify(body)).not.toContain("heightCm");
  });

  it("unknownOrMissingCollectionsAreValidationFailed", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    for (const query of ["nope", "sleep,nope", null, "", ",,"]) {
      const res = await documents(t.ids.vega, query, token);
      expect(res.status).toBe(422);
      expect((await res.json()).error.code).toBe("validation_failed");
    }
  });

  it("otherTeamPlayerIsNotFound", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    const unknown = "11111111-1111-4111-8111-111111111111";
    const results = [];
    for (const id of [t.ids.outsider, t.ids.solo, t.ids.otherStaff, t.ids.staff, unknown, "not-a-uuid"]) {
      const res = await documents(id, "sleep", token);
      expect(res.status).toBe(404);
      results.push(await res.json());
    }
    for (const body of results) expect(body.error.code).toBe("not_found");
    expect(JSON.stringify(results)).not.toContain("sessions");
  });
});

describe("GET /v1/staff/players/{id}/photos/{meal_id}", () => {
  it("photoOfTeamPlayer", async () => {
    const t = await seed();
    await storePhoto(t.ids.vega, MEAL);
    const res = await photo(t.ids.vega, MEAL, await tokenOf(t.ids.staff));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("image/jpeg");
    expect(res.headers.get("cache-control")).toBe("private, no-store");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(JPEG);
  });

  it("photoOfOtherTeamIsNotFound", async () => {
    const t = await seed();
    await storePhoto(t.ids.outsider, OTHER_MEAL);
    await storePhoto(t.ids.solo, OTHER_MEAL);
    await storePhoto(t.ids.vega, MEAL);
    const token = await tokenOf(t.ids.staff);
    const misses = [
      await photo(t.ids.outsider, OTHER_MEAL, token),
      await photo(t.ids.solo, OTHER_MEAL, token),
      await photo(t.ids.vega, OTHER_MEAL, token), // a meal of someone else under a team player's id
      await photo(t.ids.vega, "not-a-uuid", token),
    ];
    for (const res of misses) {
      expect(res.status).toBe(404);
      expect(res.headers.get("content-type")).not.toBe("image/jpeg");
      expect((await res.json()).error.code).toBe("not_found");
    }
  });
});

describe("rate limits", () => {
  it("staffBucketLimits", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    await exhaust("staff", t.ids.staff, 120);
    // The 121st request of the window is refused, on both JSON endpoints.
    const first = await team(token);
    expect(first.status).toBe(429);
    expect((await first.json()).error.code).toBe("rate_limited");
    expect((await documents(t.ids.vega, "sleep", token)).status).toBe(429);
    // Photos count in their own, larger bucket and are not affected.
    await storePhoto(t.ids.vega, MEAL);
    expect((await photo(t.ids.vega, MEAL, token)).status).toBe(200);
  });

  it("staffBucketAllowsTheLastRequest", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    await exhaust("staff", t.ids.staff, 119);
    expect((await team(token)).status).toBe(200);
    expect((await team(token)).status).toBe(429);
  });

  it("photoBucketIsThePhotosBucket", async () => {
    const t = await seed();
    const token = await tokenOf(t.ids.staff);
    await storePhoto(t.ids.vega, MEAL);
    await exhaust("photos", t.ids.staff, 600);
    const res = await photo(t.ids.vega, MEAL, token);
    expect(res.status).toBe(429);
    expect((await res.json()).error.code).toBe("rate_limited");
    expect((await team(token)).status).toBe(200);
  });
});
