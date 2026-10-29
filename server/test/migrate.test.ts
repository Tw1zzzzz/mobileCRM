import { describe, expect, it } from "vitest";
import { shouldMigrate } from "../db/migrate";

describe("migrations at build time", () => {
  it("runOnlyForProductionOrLocalBuilds", () => {
    expect(shouldMigrate({ VERCEL_ENV: "production" })).toBe(true);
    expect(shouldMigrate({})).toBe(true);
    // Preview (and `vercel dev`) builds share the production DATABASE_URL: they must not change its schema.
    expect(shouldMigrate({ VERCEL_ENV: "preview" })).toBe(false);
    expect(shouldMigrate({ VERCEL_ENV: "development" })).toBe(false);
  });
});
