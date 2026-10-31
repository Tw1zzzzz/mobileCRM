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
    expect(rules.filter((r) => !r.source.startsWith("/staff/"))).toEqual([]);
  });

  it("uploadedImagesAreSandboxedEvenIfOpenedAsAPage", async () => {
    const rules = (await config.headers?.()) ?? [];
    const files = rules.filter((r) => r.source === "/staff/files/:path*");
    expect(files).toHaveLength(1);
    // it comes AFTER the general staff rule, which sets another policy for the same header
    expect(rules.indexOf(files[0])).toBeGreaterThan(rules.findIndex((r) => r.source === "/staff/:path*"));
    const csp = files[0].headers.find((h) => h.key === "Content-Security-Policy")?.value ?? "";
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain("sandbox");
    expect(csp).toContain("frame-ancestors 'none'");
  });

  it("serverActionsAcceptA3MbUploadForTheTwoMbImages", () => {
    // 2 000 000 bytes of image + multipart overhead must reach the action (to say «Файл больше 2 МБ»), and stay
    // below the 4.5 MB request body cap of Vercel functions.
    expect(config.experimental?.serverActions?.bodySizeLimit).toBe("3mb");
  });
});
