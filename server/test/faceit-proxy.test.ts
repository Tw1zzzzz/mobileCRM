import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { handle } from "../lib/http";
import { proxyFaceit } from "../lib/faceit/proxy";
import { resetDb, testDeps } from "./helpers";
import { addMember, bearer, jsonRequest } from "./team-fixture";

const deps = testDeps();
const PLAYER = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

let token: string;
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(async () => {
  await resetDb(deps.sql);
  token = await bearer(deps, await addMember(deps, "nova", "soloPlayer", null));
  process.env.FACEIT_API_KEY = "server-faceit-key";
  fetchMock = vi.fn(async () => Response.json({ ok: true }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  process.env.FACEIT_API_KEY = "";
});
afterAll(() => deps.sql.end());

const call = (path: string[], query = "", authToken: string | null = token) =>
  handle(() => proxyFaceit(jsonRequest(`http://localhost/v1/faceit/${path.join("/")}${query}`, "GET", authToken ?? undefined), deps, path));
const errorOf = async (res: Response) => ({ status: res.status, code: (await res.json()).error.code as string });

describe("access", () => {
  it("needsASignedInUser", async () => {
    expect((await errorOf(await call(["players"], "?nickname=nova", null))).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("answers503WithoutAServerKeyAndNeverCallsFaceit", async () => {
    process.env.FACEIT_API_KEY = "  ";
    expect(await errorOf(await call(["players"], "?nickname=nova"))).toEqual({ status: 503, code: "internal" });
    delete process.env.FACEIT_API_KEY;
    expect((await call(["players"], "?nickname=nova")).status).toBe(503);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("fixed upstream destinations", () => {
  const upstream = () => fetchMock.mock.calls.map((c) => c[0] as string);

  it("mapsEachAllowedPathToOneFaceitUrlWithTheServerKey", async () => {
    expect((await call(["players"], "?nickname=Nova%20Prime")).status).toBe(200);
    expect((await call(["players", PLAYER, "stats"])).status).toBe(200);
    expect((await call(["players", PLAYER, "games", "cs2", "stats"])).status).toBe(200);
    expect((await call(["rankings", "games", "cs2", "regions", "EU", "players", PLAYER])).status).toBe(200);
    expect(upstream()).toEqual([
      "https://open.faceit.com/data/v4/players?nickname=Nova%20Prime&game=cs2",
      `https://open.faceit.com/data/v4/players/${PLAYER}/stats/cs2`,
      `https://open.faceit.com/data/v4/players/${PLAYER}/games/cs2/stats?limit=100`,
      `https://open.faceit.com/data/v4/rankings/games/cs2/regions/EU/players/${PLAYER}?limit=1`,
    ]);
    for (const [, init] of fetchMock.mock.calls) {
      expect((init as RequestInit).headers).toEqual({ Authorization: "Bearer server-faceit-key", Accept: "application/json" });
      expect((init as RequestInit).redirect).toBe("error");
    }
  });

  it("neverForwardsTheCallersQueryOrTheGame", async () => {
    await call(["players", PLAYER, "games", "cs2", "stats"], "?limit=1000&offset=5&game=csgo");
    await call(["players"], "?nickname=nova&game=csgo&limit=500");
    expect(upstream()).toEqual([
      `https://open.faceit.com/data/v4/players/${PLAYER}/games/cs2/stats?limit=100`,
      "https://open.faceit.com/data/v4/players?nickname=nova&game=cs2",
    ]);
  });

  it("escapesTheNickname", async () => {
    await call(["players"], `?nickname=${encodeURIComponent("a&game=csgo#x/../y")}`);
    expect(upstream()).toEqual([`https://open.faceit.com/data/v4/players?nickname=${encodeURIComponent("a&game=csgo#x/../y")}&game=cs2`]);
  });

  it("refusesUnknownPaths", async () => {
    for (const path of [
      [], ["matches", "1"], ["players", PLAYER], ["players", PLAYER, "history"], ["players", PLAYER, "stats", "extra"],
      ["players", PLAYER, "games", "csgo", "stats"], ["players", PLAYER, "games", "cs2", "stats", "x"], ["rankings"],
      ["rankings", "games", "csgo", "regions", "EU", "players", PLAYER], ["data", "v4", "players"], ["..", "players"],
    ]) {
      expect(await errorOf(await call(path))).toEqual({ status: 404, code: "not_found" });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refusesIdsThatAreNotUuids", async () => {
    for (const id of ["nova", "123", `${PLAYER}/../x`, "..", `${PLAYER}x`, "0a1b2c3d-4e5f-4a6b-8c7d", "%2e%2e"]) {
      expect((await call(["players", id, "stats"])).status).toBe(404);
      expect((await call(["players", id, "games", "cs2", "stats"])).status).toBe(404);
      expect((await call(["rankings", "games", "cs2", "regions", "EU", "players", id])).status).toBe(404);
    }
    for (const region of ["", "e", "EU/../x", "a".repeat(13), "e u", "eu?x=1"]) {
      expect((await call(["rankings", "games", "cs2", "regions", region, "players", PLAYER])).status).toBe(404);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("validatesTheNickname", async () => {
    for (const query of ["", "?nickname=", "?nickname=%20%20", `?nickname=${"a".repeat(41)}`]) {
      expect(await errorOf(await call(["players"], query))).toEqual({ status: 422, code: "validation_failed" });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("upstream answers", () => {
  it("passesTheDataThroughInTheEnvelope", async () => {
    fetchMock.mockResolvedValueOnce(Response.json({ player_id: PLAYER, nickname: "nova" }));
    const res = await call(["players"], "?nickname=nova");
    expect(await res.json()).toEqual({ success: true, data: { player_id: PLAYER, nickname: "nova" }, error: null });
  });

  it("maps404To404", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 404 }));
    expect(await errorOf(await call(["players"], "?nickname=ghost"))).toEqual({ status: 404, code: "not_found" });
  });

  it("maps429To429", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 429 }));
    expect(await errorOf(await call(["players", PLAYER, "stats"]))).toEqual({ status: 429, code: "rate_limited" });
  });

  it("mapsOtherFailuresTo502", async () => {
    for (const status of [400, 401, 403, 500, 503]) {
      fetchMock.mockResolvedValueOnce(new Response("{}", { status }));
      expect(await errorOf(await call(["players", PLAYER, "stats"]))).toEqual({ status: 502, code: "internal" });
    }
  });

  it("mapsNetworkErrorsAndTimeoutsTo502", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    expect((await call(["players", PLAYER, "stats"])).status).toBe(502);
    fetchMock.mockRejectedValueOnce(new DOMException("timeout", "TimeoutError"));
    expect((await call(["players", PLAYER, "stats"])).status).toBe(502);
  });

  it("mapsAnUnreadableBodyTo502", async () => {
    fetchMock.mockResolvedValueOnce(new Response("<html>", { status: 200 }));
    expect(await errorOf(await call(["players", PLAYER, "stats"]))).toEqual({ status: 502, code: "internal" });
  });

  it("neverLeaksTheKeyInAnError", async () => {
    fetchMock.mockRejectedValueOnce(new Error("failed with Bearer server-faceit-key"));
    const text = await (await call(["players", PLAYER, "stats"])).text();
    expect(text).not.toContain("server-faceit-key");
  });
});

describe("rate limit", () => {
  it("allows120CallsAMinutePerUser", async () => {
    const [user] = await deps.sql<{ id: string }[]>`select id from users limit 1`;
    const windowStart = new Date(Math.floor(deps.now().getTime() / 60_000) * 60_000);
    await deps.sql`insert into rate_limits (key, window_start, count) values (${`faceit:${user.id}`}, ${windowStart}, 120)`;
    expect(await errorOf(await call(["players", PLAYER, "stats"]))).toEqual({ status: 429, code: "rate_limited" });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
