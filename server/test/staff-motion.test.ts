import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const root = fileURLToPath(new URL("..", import.meta.url));

function stylesheets(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return stylesheets(path);
    return entry.name.endsWith(".css") ? [path] : [];
  });
}

const shell = readFileSync(join(root, "app/staff/staff.module.css"), "utf8");
const sheets = [...stylesheets(join(root, "app/staff")), ...stylesheets(join(root, "components/staff"))].map((path) => ({
  path,
  css: readFileSync(path, "utf8"),
}));
/** Every `transition`/`animation` declaration of the panel, except the tokens themselves. */
const motion = sheets.flatMap(({ path, css }) =>
  (css.match(/^\s*(?:transition|animation)(?:-[a-z-]+)?:[^;]*;/gm) ?? []).map((line) => ({ path, line: line.trim() })),
);

describe("staff panel motion", () => {
  it("timingLivesInTokensOnThePanelRoot", () => {
    const tokens = /\.root \{([^}]*)\}/.exec(shell)?.[1] ?? "";
    for (const name of ["--motion-fast", "--motion-base", "--motion-entry", "--motion-draw", "--motion-stagger", "--ease-soft", "--transition-soft"]) {
      expect(tokens, name).toContain(`${name}:`);
    }
    expect(motion.length).toBeGreaterThan(0);
    for (const { path, line } of motion) {
      expect(`${path}: ${line}`).not.toMatch(/: .*\b\d+m?s\b/);
      expect(`${path}: ${line}`).not.toContain("cubic-bezier");
    }
  });

  it("reducedMotionSwitchesAnimationsOffForTheWholePanel", () => {
    const block = /@media \(prefers-reduced-motion: reduce\) \{\s*\.root \*,[^{]*\{([^}]*)\}/.exec(shell)?.[1] ?? "";
    expect(block).toMatch(/animation:\s*none !important/);
    expect(block).toMatch(/transition:\s*none !important/);
  });

  it("nothingDependsOnAnAnimationToBecomeVisible", () => {
    // The look at rest is the end state; keyframes only say where the motion comes from.
    for (const { path, line } of motion) expect(`${path}: ${line}`).not.toMatch(/\b(?:forwards|both)\b/);
    for (const { path, css } of sheets) expect(`${path}: ${css}`).not.toMatch(/stroke-dashoffset:\s*1\s*;\s*\n\s*(?:animation|stroke)/);
  });
});
