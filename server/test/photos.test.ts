import { randomUUID } from "node:crypto";
import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { handle } from "../lib/http";
import { registerHandler } from "../lib/auth/register";
import { verifyEmailHandler } from "../lib/auth/verify";
import { deleteMe } from "../lib/me";
import { getPhoto, putPhoto } from "../lib/photos";
import { listSync, putSyncDocument } from "../lib/sync";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const PASSWORD = "secret123";

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

async function signUp(email: string) {
  const call = async (path: string, body: unknown, fn: (req: Request, d: typeof deps) => Promise<Response>) => {
    const req = new Request(`http://localhost/v1/${path}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    const res = await handle(() => fn(req, deps));
    return { status: res.status, body: await res.json() };
  };
  const reg = await call(
    "auth/register",
    {
      nickname: "Nova",
      email,
      password: PASSWORD,
      role: "soloPlayer",
      accepted_terms: true,
      accepted_privacy: true,
      accepted_beta_terms: true,
      beta_terms_version: "2026-10-26",
    },
    registerHandler,
  );
  expect(reg.status).toBe(201);
  const res = await call("auth/verify-email", { email, code: deps.mailer.lastCode(email)! }, verifyEmailHandler);
  expect(res.status).toBe(200);
  return res.body.data as { access_token: string; user: { id: string } };
}

const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);

function authHeaders(token?: string): Record<string, string> {
  return token ? { authorization: `Bearer ${token}` } : {};
}

async function putRaw(mealId: string, bytes: Uint8Array, token?: string) {
  const req = new Request(`http://localhost/v1/photos/${mealId}`, {
    method: "PUT",
    headers: { "content-type": "image/jpeg", ...authHeaders(token) },
    body: bytes as BodyInit,
  });
  const res = await handle(() => putPhoto(req, deps, mealId));
  return { status: res.status, body: await res.json() };
}

async function getRaw(mealId: string, token?: string) {
  const req = new Request(`http://localhost/v1/photos/${mealId}`, { headers: authHeaders(token) });
  return handle(() => getPhoto(req, deps, mealId));
}

async function putNutrition(document: unknown, baseVersion: number | null, token: string) {
  const req = new Request("http://localhost/v1/sync/nutrition", {
    method: "PUT",
    headers: { "content-type": "application/json", ...authHeaders(token) },
    body: JSON.stringify({ base_version: baseVersion, document }),
  });
  const res = await handle(() => putSyncDocument(req, deps, "nutrition"));
  return { status: res.status, body: await res.json() };
}

describe("meal photos", () => {
  it("putThenGetReturnsSameBytes", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const mealId = randomUUID();
    const written = await putRaw(mealId, JPEG, token);
    expect(written.status).toBe(200);
    expect(written.body).toEqual({ success: true, data: {}, error: null });

    const res = await getRaw(mealId, token);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("image/jpeg");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(JPEG);
  });

  it("putReplacesAnExistingPhoto", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const mealId = randomUUID();
    await putRaw(mealId, JPEG, token);
    const newer = Uint8Array.from([0xff, 0xd8, 0xff, 0x01, 0x02]);
    expect((await putRaw(mealId, newer, token)).status).toBe(200);
    const res = await getRaw(mealId, token);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(newer);
  });

  it("nonJpegRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const mealId = randomUUID();
    const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const res = await putRaw(mealId, png, token);
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("validation_failed");
    expect((await getRaw(mealId, token)).status).toBe(404);
  });

  it("emptyBodyRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await putRaw(randomUUID(), new Uint8Array(0), token);
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("validation_failed");
  });

  it("oversizedPhotoRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const big = new Uint8Array(1_000_001);
    big.set([0xff, 0xd8, 0xff]);
    const res = await putRaw(randomUUID(), big, token);
    expect(res.status).toBe(413);
    expect(res.body.error.code).toBe("payload_too_large");
  });

  it("photoOfExactlyTheLimitIsAccepted", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const max = new Uint8Array(1_000_000);
    max.set([0xff, 0xd8, 0xff]);
    expect((await putRaw(randomUUID(), max, token)).status).toBe(200);
  });

  it("oversizedStreamWithoutContentLengthRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const mealId = randomUUID();
    const big = new Uint8Array(1_000_001);
    big.set([0xff, 0xd8, 0xff]);
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(big);
        controller.close();
      },
    });
    const req = new Request(`http://localhost/v1/photos/${mealId}`, {
      method: "PUT",
      headers: { "content-type": "image/jpeg", ...authHeaders(token) },
      body: stream,
      // @ts-expect-error Node's fetch requires duplex for streamed bodies
      duplex: "half",
    });
    const res = await handle(() => putPhoto(req, deps, mealId));
    expect(res.status).toBe(413);
  });

  it("invalidMealIdRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const put = await putRaw("not-a-uuid", JPEG, token);
    expect(put.status).toBe(422);
    expect(put.body.error.code).toBe("validation_failed");
    const get = await getRaw("not-a-uuid", token);
    expect(get.status).toBe(422);
    expect((await get.json()).error.code).toBe("validation_failed");
  });

  it("missingPhotoIs404", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await getRaw(randomUUID(), token);
    expect(res.status).toBe(404);
    expect((await res.json()).error.code).toBe("not_found");
  });

  it("requiresAuthentication", async () => {
    const mealId = randomUUID();
    expect((await putRaw(mealId, JPEG)).status).toBe(401);
    expect((await getRaw(mealId)).status).toBe(401);
  });

  it("photosArePerUser", async () => {
    const a = await signUp("a@team.gg");
    const b = await signUp("b@team.gg");
    const mealId = randomUUID();
    await putRaw(mealId, JPEG, a.access_token);
    expect((await getRaw(mealId, b.access_token)).status).toBe(404);

    const other = Uint8Array.from([0xff, 0xd8, 0xff, 0x09]);
    expect((await putRaw(mealId, other, b.access_token)).status).toBe(200);
    const mine = await getRaw(mealId, a.access_token);
    expect(new Uint8Array(await mine.arrayBuffer())).toEqual(JPEG);
  });

  it("nutritionWriteDropsOrphanPhotos", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    const m2 = randomUUID();
    await putRaw(m1, JPEG, token);
    await putRaw(m2, JPEG, token);

    const first = await putNutrition({ meals: [{ id: m1 }, { id: m2 }] }, null, token);
    expect(first.status).toBe(200);
    expect((await getRaw(m2, token)).status).toBe(200);

    const second = await putNutrition({ meals: [{ id: m1 }] }, 1, token);
    expect(second.status).toBe(200);
    expect((await getRaw(m1, token)).status).toBe(200);
    expect((await getRaw(m2, token)).status).toBe(404);
  });

  it("nutritionMealIdMatchIgnoresCase", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    await putRaw(m1, JPEG, token);
    const res = await putNutrition({ meals: [{ id: m1.toUpperCase() }] }, null, token);
    expect(res.status).toBe(200);
    expect((await getRaw(m1, token)).status).toBe(200);
  });

  it("nutritionWithEmptyMealsDropsEveryPhoto", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    await putRaw(m1, JPEG, token);
    expect((await putNutrition({ meals: [] }, null, token)).status).toBe(200);
    expect((await getRaw(m1, token)).status).toBe(404);
  });

  it("nutritionWithoutMealsListKeepsPhotos", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    await putRaw(m1, JPEG, token);
    expect((await putNutrition({ goals: { kcal: 2000 } }, null, token)).status).toBe(200);
    expect((await putNutrition({ meals: "oops" }, 1, token)).status).toBe(200);
    expect((await putNutrition({ meals: { id: m1 } }, 2, token)).status).toBe(200);
    expect((await getRaw(m1, token)).status).toBe(200);
  });

  it("nutritionCleanupOnlyTouchesTheWritersPhotos", async () => {
    const a = await signUp("a@team.gg");
    const b = await signUp("b@team.gg");
    const mealId = randomUUID();
    await putRaw(mealId, JPEG, b.access_token);
    expect((await putNutrition({ meals: [] }, null, a.access_token)).status).toBe(200);
    expect((await getRaw(mealId, b.access_token)).status).toBe(200);
  });

  it("rejectedNutritionWriteKeepsPhotos", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    await putRaw(m1, JPEG, token);
    expect((await putNutrition({ meals: [{ id: m1 }] }, null, token)).status).toBe(200);
    const stale = await putNutrition({ meals: [] }, 7, token);
    expect(stale.status).toBe(409);
    expect((await getRaw(m1, token)).status).toBe(200);
  });

  it("otherCollectionsLeavePhotosAlone", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const m1 = randomUUID();
    await putRaw(m1, JPEG, token);
    const req = new Request("http://localhost/v1/sync/sleep", {
      method: "PUT",
      headers: { "content-type": "application/json", ...authHeaders(token) },
      body: JSON.stringify({ base_version: null, document: { meals: [] } }),
    });
    expect((await handle(() => putSyncDocument(req, deps, "sleep"))).status).toBe(200);
    expect((await getRaw(m1, token)).status).toBe(200);
  });

  it("deleteMeRemovesPhotos", async () => {
    const { access_token: token, user } = await signUp("nova@team.gg");
    await putRaw(randomUUID(), JPEG, token);
    const before = await deps.sql`select 1 from meal_photos where user_id = ${user.id}`;
    expect(before).toHaveLength(1);
    const req = new Request("http://localhost/v1/me", { method: "DELETE", headers: authHeaders(token) });
    expect((await handle(() => deleteMe(req, deps))).status).toBe(200);
    const after = await deps.sql`select 1 from meal_photos`;
    expect(after).toHaveLength(0);
  });

  it("photosHaveTheirOwnRateLimit", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const listSyncStatus = async () => {
      const req = new Request("http://localhost/v1/sync", { headers: authHeaders(token) });
      return (await handle(() => listSync(req, deps))).status;
    };
    // The sync bucket (120/min) is used up; photos still go through.
    for (let i = 0; i < 120; i++) expect(await listSyncStatus()).toBe(200);
    expect(await listSyncStatus()).toBe(429);
    const meal = randomUUID();
    expect((await putRaw(meal, JPEG, token)).status).toBe(200);

    // Photos: 600 per minute, then 429 — while sync stays limited on its own.
    for (let i = 1; i < 600; i++) expect((await getRaw(meal, token)).status).toBe(200);
    const limited = await getRaw(meal, token);
    expect(limited.status).toBe(429);
    expect((await limited.json()).error.code).toBe("rate_limited");

    // A new minute opens both buckets again.
    deps.advance(60_000);
    expect(await listSyncStatus()).toBe(200);
    expect((await getRaw(meal, token)).status).toBe(200);
  });
});
