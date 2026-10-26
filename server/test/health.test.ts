import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "../app/v1/health/route";
import { defaultDeps } from "../lib/deps";
import { fail, handle } from "../lib/http";
import { resetDb, testDeps } from "./helpers";

const deps = testDeps();

beforeEach(() => resetDb(deps.sql));
afterAll(async () => {
  await deps.sql.end();
  await defaultDeps().sql.end();
});

describe("health", () => {
  it("returns ok with a live database", async () => {
    const res = await GET(new Request("http://localhost/v1/health"));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, data: { ok: true, db: true }, error: null });
  });
});

describe("envelope", () => {
  it("fail() builds the error envelope", async () => {
    const res = fail("email_taken", "x", 409);
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({
      success: false,
      data: null,
      error: { code: "email_taken", message: "x" },
    });
  });

  it("handle() hides unexpected errors behind internal 500", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const res = await handle(async () => {
      throw new Error("boom");
    });
    const text = await res.text();
    expect(res.status).toBe(500);
    expect(JSON.parse(text).error.code).toBe("internal");
    expect(text).not.toContain("boom");
    expect(spy).toHaveBeenCalled();
    spy.mockRestore();
  });
});
