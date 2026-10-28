import { afterAll, beforeEach, describe, expect, it } from "vitest";
import { handle } from "../lib/http";
import { registerHandler } from "../lib/auth/register";
import { verifyEmailHandler } from "../lib/auth/verify";
import { deleteMe } from "../lib/me";
import { COLLECTIONS, getSyncDocument, listSync, putSyncDocument } from "../lib/sync";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();
const PASSWORD = "secret123";

beforeEach(async () => {
  deps.mailer.sent.length = 0;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

interface Reply {
  status: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  body: any;
}

async function send(
  fn: (req: Request, d: typeof deps) => Promise<Response>,
  method: string,
  path: string,
  body?: unknown,
  token?: string,
  extraHeaders: Record<string, string> = {},
): Promise<Reply> {
  const req = new Request(`http://localhost/v1/${path}`, {
    method,
    headers: {
      ...(body === undefined ? {} : { "content-type": "application/json" }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...extraHeaders,
    },
    body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
  const res = await handle(() => fn(req, deps));
  return { status: res.status, body: await res.json() };
}

async function signUp(email: string) {
  const reg = await send(registerHandler, "POST", "auth/register", {
    nickname: "Nova",
    email,
    password: PASSWORD,
    role: "soloPlayer",
    accepted_terms: true,
    accepted_privacy: true,
    accepted_beta_terms: true,
    beta_terms_version: "2026-10-26",
  });
  expect(reg.status).toBe(201);
  const res = await send(verifyEmailHandler, "POST", "auth/verify-email", {
    email,
    code: deps.mailer.lastCode(email)!,
  });
  expect(res.status).toBe(200);
  return res.body.data as { access_token: string; user: { id: string } };
}

const list = (token?: string) => send(listSync, "GET", "sync", undefined, token);
const get = (collection: string, token?: string) =>
  send((req, d) => getSyncDocument(req, d, collection), "GET", `sync/${collection}`, undefined, token);
const put = (collection: string, body: unknown, token?: string, headers?: Record<string, string>) =>
  send((req, d) => putSyncDocument(req, d, collection), "PUT", `sync/${collection}`, body, token, headers);

const DOC = { entries: [{ id: "a1", hours: 7.5, note: "ok", tags: ["x", null] }], updatedAt: "2026-10-26T08:00:00Z" };

describe("sync documents", () => {
  it("putThenGetReturnsSameDocument", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const written = await put("sleep", { base_version: null, document: DOC }, token);
    expect(written.status).toBe(200);
    expect(written.body.success).toBe(true);
    expect(written.body.data.version).toBe(1);
    expect(written.body.data.updated_at).toBe(deps.now().toISOString());

    const read = await get("sleep", token);
    expect(read.status).toBe(200);
    expect(read.body.data).toEqual({
      collection: "sleep",
      version: 1,
      updated_at: deps.now().toISOString(),
      document: DOC,
    });
  });

  it("staleBaseVersionConflicts", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    expect((await put("sleep", { base_version: null, document: DOC }, token)).status).toBe(200);

    const second = await put("sleep", { base_version: null, document: { other: true } }, token);
    expect(second.status).toBe(409);
    expect(second.body).toEqual({
      success: false,
      data: null,
      error: { code: "version_conflict", message: null },
      meta: { current_version: 1 },
    });
    const stored = await get("sleep", token);
    expect(stored.body.data.document).toEqual(DOC);
    expect(stored.body.data.version).toBe(1);

    const stale = await put("sleep", { base_version: 0, document: { other: true } }, token);
    expect(stale.status).toBe(409);
    expect(stale.body.meta).toEqual({ current_version: 1 });
  });

  it("baseVersionForMissingDocumentConflicts", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await put("sleep", { base_version: 3, document: DOC }, token);
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe("version_conflict");
    expect(await deps.sql`select 1 from sync_documents`).toHaveLength(0);
  });

  it("versionIncrementsOnEachWrite", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    expect((await put("sleep", { base_version: null, document: DOC }, token)).body.data.version).toBe(1);
    expect((await put("sleep", { base_version: 1, document: { v: 2 } }, token)).body.data.version).toBe(2);
    await put("lineups", { base_version: null, document: [] }, token);

    const listed = await list(token);
    expect(listed.status).toBe(200);
    expect(listed.body.data).toHaveLength(2);
    expect(listed.body.data).toContainEqual({
      collection: "sleep",
      version: 2,
      updated_at: deps.now().toISOString(),
    });
    expect(listed.body.data).toContainEqual({
      collection: "lineups",
      version: 1,
      updated_at: deps.now().toISOString(),
    });
  });

  it("concurrentWritesWithSameBaseOnlyOneWins", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    await put("sleep", { base_version: null, document: DOC }, token);
    const results = await Promise.all(
      Array.from({ length: 6 }, (_, i) => put("sleep", { base_version: 1, document: { writer: i } }, token)),
    );
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(results.filter((r) => r.status === 409)).toHaveLength(5);
    expect((await get("sleep", token)).body.data.version).toBe(2);
  });

  it("emptyListWhenNothingStored", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await list(token);
    expect(res.status).toBe(200);
    expect(res.body.data).toEqual([]);
  });

  it("missingDocumentIsNotFound", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await get("sleep", token);
    expect(res.status).toBe(404);
    expect(res.body.error.code).toBe("not_found");
  });

  it("exactlyTheTwelveCollectionsAreAccepted", async () => {
    expect([...COLLECTIONS].sort()).toEqual(
      [
        "body-profile", "calendar", "cognitive", "faceit", "fitness-state", "game-goals",
        "lineups", "nutrition", "player-survey", "screen-time", "sleep", "wellbeing",
      ],
    );
    const { access_token: token } = await signUp("nova@team.gg");
    for (const name of COLLECTIONS) {
      expect((await put(name, { base_version: null, document: { name } }, token)).status, name).toBe(200);
    }
  });

  it("unknownCollectionRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    for (const name of ["session", "accounts", "sync-ledger", "sync-photos", "Sleep", "nope"]) {
      const written = await put(name, { base_version: null, document: {} }, token);
      expect(written.status, name).toBe(422);
      expect(written.body.error.code).toBe("validation_failed");
      expect((await get(name, token)).status, name).toBe(422);
    }
    expect(await deps.sql`select 1 from sync_documents`).toHaveLength(0);
  });

  it("invalidBodiesRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const cases: unknown[] = [
      {},
      { document: DOC },
      { base_version: null },
      { base_version: "1", document: DOC },
      { base_version: 1.5, document: DOC },
      { base_version: -1, document: DOC },
    ];
    for (const body of cases) {
      const res = await put("sleep", body, token);
      expect(res.status, JSON.stringify(body)).toBe(422);
      expect(res.body.error.code).toBe("validation_failed");
    }
    const notJson = await put("sleep", "{oops", token);
    expect(notJson.status).toBe(400);
    expect(notJson.body.error.code).toBe("bad_request");
    expect(await deps.sql`select 1 from sync_documents`).toHaveLength(0);
  });

  it("nulCharacterIsValidationFailedNotServerError", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const res = await put("sleep", { base_version: null, document: { note: "a\u0000b" } }, token);
    expect(res.status).toBe(422);
    expect(res.body.error.code).toBe("validation_failed");
    expect(await deps.sql`select 1 from sync_documents`).toHaveLength(0);
  });

  it("documentsArePerUser", async () => {
    const a = await signUp("a@team.gg");
    const b = await signUp("b@team.gg");
    await put("sleep", { base_version: null, document: DOC }, a.access_token);

    const asB = await get("sleep", b.access_token);
    expect(asB.status).toBe(404);
    expect((await list(b.access_token)).body.data).toEqual([]);

    // B starts their own history at version 1 and does not disturb A's.
    expect((await put("sleep", { base_version: null, document: { mine: 1 } }, b.access_token)).body.data.version).toBe(1);
    expect((await get("sleep", a.access_token)).body.data.document).toEqual(DOC);
  });

  it("oversizedDocumentRejected", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const big = JSON.stringify({ base_version: null, document: { blob: "x".repeat(4_000_001) } });
    expect(Buffer.byteLength(big)).toBeGreaterThan(4_000_000);

    const actual = await put("sleep", big, token);
    expect(actual.status).toBe(413);
    expect(actual.body.error.code).toBe("payload_too_large");

    const declared = await put("sleep", "{}", token, { "content-length": "4000001" });
    expect(declared.status).toBe(413);
    expect(declared.body.error.code).toBe("payload_too_large");

    expect(await deps.sql`select 1 from sync_documents`).toHaveLength(0);
  });

  it("documentJustUnderTheLimitIsAccepted", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    const prefix = JSON.stringify({ base_version: null, document: { blob: "" } });
    const blob = "x".repeat(4_000_000 - Buffer.byteLength(prefix));
    const body = JSON.stringify({ base_version: null, document: { blob } });
    expect(Buffer.byteLength(body)).toBe(4_000_000);
    expect((await put("sleep", body, token)).status).toBe(200);
  });

  it("requiresAuth", async () => {
    for (const reply of [
      await list(),
      await get("sleep"),
      await put("sleep", { base_version: null, document: DOC }),
      await put("sleep", { base_version: null, document: DOC }, "not-a-jwt"),
    ]) {
      expect(reply.status).toBe(401);
      expect(reply.body.error.code).toBe("unauthorized");
    }
  });

  it("limitsRequestsPerUser", async () => {
    const { access_token: token } = await signUp("nova@team.gg");
    for (let i = 0; i < 120; i++) expect((await list(token)).status).toBe(200);
    const res = await list(token);
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe("rate_limited");
  });

  it("deleteMeRemovesDocuments", async () => {
    const { access_token: token, user } = await signUp("nova@team.gg");
    await put("sleep", { base_version: null, document: DOC }, token);
    expect(await deps.sql`select 1 from sync_documents where user_id = ${user.id}`).toHaveLength(1);

    const del = await send(deleteMe, "DELETE", "me", undefined, token);
    expect(del.status).toBe(200);
    expect(await deps.sql`select 1 from sync_documents where user_id = ${user.id}`).toHaveLength(0);
  });
});
