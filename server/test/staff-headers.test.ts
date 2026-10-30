import { describe, expect, it } from "vitest";
import config from "../next.config";

describe("staff panel response headers", () => {
  it("staffPagesCannotBeFramed", async () => {
    const rules = (await config.headers?.()) ?? [];
    const staff = rules.find((r) => r.source === "/staff/:path*");
    expect(staff).toBeDefined();
    const byKey = Object.fromEntries((staff?.headers ?? []).map((h) => [h.key, h.value]));
    expect(byKey["X-Frame-Options"]).toBe("DENY");
    expect(byKey["Content-Security-Policy"]).toBe("frame-ancestors 'none'");
  });

  it("apiRoutesAreLeftAlone", async () => {
    const rules = (await config.headers?.()) ?? [];
    expect(rules.filter((r) => r.source !== "/staff/:path*")).toEqual([]);
  });
});
