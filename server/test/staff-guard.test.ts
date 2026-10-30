import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Guards against a new staff page or Server Action shipping without the session check. These are plain text
 * checks on the source, not a proof: they look at the file as written and cannot follow helpers, renamed
 * imports or checks done indirectly. A page that needs something unusual must adjust this test on purpose.
 *
 * Why it matters: Next renders layouts and pages independently, so the panel layout's redirect does not
 * protect a page's data fetching, and a Server Action is a public POST endpoint.
 */

const STAFF_DIR = join(__dirname, "..", "app", "staff");
const PANEL_DIR = join(STAFF_DIR, "(panel)");

/** Server Actions that run for logged-out visitors on purpose. */
const PUBLIC_ACTIONS = new Set(["signInAction", "requestResetAction", "confirmResetAction", "signOutAction"]);

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? walk(join(dir, entry.name)) : [join(dir, entry.name)],
  );
}

const rel = (file: string): string => relative(join(__dirname, ".."), file);

describe("staff panel guard", () => {
  const pages = walk(PANEL_DIR).filter((f) => f.endsWith("/page.tsx"));

  it("findsThePanelPages", () => {
    // If the glob silently matched nothing, the checks below would pass for the wrong reason.
    expect(pages.map(rel)).toEqual(
      expect.arrayContaining(["app/staff/(panel)/overview/page.tsx", "app/staff/(panel)/players/page.tsx"]),
    );
  });

  it("everyPanelPageChecksTheSessionBeforeAnythingElse", () => {
    for (const file of pages) {
      const source = readFileSync(file, "utf8");
      const start = source.search(/export default (async )?function/);
      expect(start, `${rel(file)}: no default exported function`).toBeGreaterThanOrEqual(0);
      const body = source.slice(start);
      // The first `await` of the page must be the session check.
      const firstAwait = /\bawait\s+([A-Za-z_$][\w$.]*)/.exec(body);
      expect(firstAwait?.[1], `${rel(file)}: first await is not requireStaffContext()`).toBe("requireStaffContext");
    }
  });

  const actionFiles = walk(STAFF_DIR).filter((f) => /\/actions\.tsx?$/.test(f));

  it("findsTheServerActions", () => {
    expect(actionFiles.map(rel)).toContain("app/staff/actions.ts");
  });

  it("everyNonPublicServerActionChecksTheSession", () => {
    const checked: string[] = [];
    for (const file of actionFiles) {
      // Split into one chunk per exported function; a chunk ends where the next export starts.
      const chunks = readFileSync(file, "utf8").split(/^export (?=(?:async )?function)/m).slice(1);
      for (const chunk of chunks) {
        const name = /^(?:async )?function\s+(\w+)/.exec(chunk)?.[1];
        expect(name, `${rel(file)}: unparsable export`).toBeDefined();
        if (PUBLIC_ACTIONS.has(name as string)) continue;
        checked.push(name as string);
        const firstAwait = /\bawait\s+([A-Za-z_$][\w$.]*)/.exec(chunk);
        expect(firstAwait?.[1], `${name}: first await is not requireStaffContext()`).toBe("requireStaffContext");
      }
    }
    // The two team actions exist today; seeing them proves the parser found real functions.
    expect(checked).toEqual(expect.arrayContaining(["rotateCodeAction", "removeMemberAction"]));
  });

  it("allExportsOfAnActionsFileAreFunctions", () => {
    // A `export const fooAction = ...` would escape the function-based parser above.
    for (const file of actionFiles) {
      expect(readFileSync(file, "utf8"), rel(file)).not.toMatch(/^export (const|let|var|default)\b/m);
    }
  });
});
