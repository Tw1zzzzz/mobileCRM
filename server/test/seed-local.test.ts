import { describe, expect, it } from "vitest";
import { hostOf, isLocalDatabase } from "../scripts/seed-local";

describe("local seed guard", () => {
  it("acceptsOnlyLocalHosts", () => {
    for (const url of ["postgres://localhost/pulse_dev", "postgres://u:p@127.0.0.1:5432/pulse_dev", "postgres://[::1]/pulse_dev", "postgres://LOCALHOST/x"]) {
      expect(isLocalDatabase(url), url).toBe(true);
    }
  });

  it("refusesEverythingElse", () => {
    for (const url of [
      "postgres://db.example.com/pulse",
      "postgres://ep-cool-123.neon.tech/neondb?sslmode=require",
      "postgres://localhost.evil.com/pulse",
      "postgres://user@evil.com:5432/localhost",
      "not a url",
      "",
      undefined,
    ]) {
      expect(isLocalDatabase(url), String(url)).toBe(false);
    }
  });

  it("namesTheHostItRefused", () => {
    expect(hostOf("postgres://db.example.com/pulse")).toBe("db.example.com");
    expect(hostOf("nonsense")).toBeNull();
  });
});
