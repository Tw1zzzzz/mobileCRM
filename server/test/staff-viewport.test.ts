import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const css = readFileSync(fileURLToPath(new URL("../app/staff/staff.module.css", import.meta.url)), "utf8");

/** The declaration block of the first rule whose selector list contains `needle`. */
function ruleFor(needle: string): string {
  const match = new RegExp(`[^{}]*${needle}[^{}]*\\{([^}]*)\\}`).exec(css);
  expect(match, `no rule for ${needle}`).not.toBeNull();
  return match?.[1] ?? "";
}

describe("staff stylesheet fills the whole viewport", () => {
  it("htmlAndBodyHaveNoMarginAndTheDarkBackground", () => {
    for (const el of ["html", "body"]) {
      const block = ruleFor(`:global\\(${el}\\)`);
      expect(block).toMatch(/margin:\s*0\s*;/);
      expect(block).toMatch(/background:\s*#070b17\s*;/i);
    }
  });

  it("theHtmlBackgroundMatchesTheBgToken", () => {
    const token = /--bg:\s*(#[0-9a-f]{6})\s*;/i.exec(css)?.[1];
    expect(token?.toLowerCase()).toBe("#070b17");
  });

  it("scrollbarsAndFormControlsAreDark", () => {
    expect(ruleFor(":global\\(html\\)")).toMatch(/color-scheme:\s*dark\s*;/);
  });

  it("onlyStaffPagesAreAffected", () => {
    // Every global html/body selector is scoped to a page that renders the staff root,
    // so the root page and /v1 keep the browser defaults.
    const lists = css.match(/[^{}]*:global\((?:html|body)\)[^{]*(?=\{)/g) ?? [];
    const selectors = lists.flatMap((list) => list.replace(/\/\*[\s\S]*?\*\//g, "").split(","));
    expect(selectors.length).toBeGreaterThanOrEqual(2);
    for (const selector of selectors) expect(selector.trim()).toMatch(/^:global\((html|body)\):has\(\.root\)$/);
  });
});
