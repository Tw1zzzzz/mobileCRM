import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Guards against a new staff page or Server Action shipping without the session check. These are plain text
 * checks on the source, not a proof: they look at the file as written and cannot follow helpers, renamed
 * imports or checks done indirectly. A page that needs something unusual must adjust this test on purpose.
 *
 * Why it matters: Next renders layouts and pages independently, so the panel layout's redirect does not
 * protect a page's data fetching, and a Server Action is a public POST endpoint. A route handler (`route.ts`,
 * e.g. the meal photos) is reachable by anyone who knows the URL and never passes through the layout at all.
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

const HTTP_METHODS = new Set(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"]);

/**
 * What is wrong with a `route.ts` source, as messages (empty when it is fine). Every exported HTTP method handler
 * must make `requireStaffContext()` its first `await`. Re-exports and default exports are refused because the
 * handler body cannot be inspected there; a handler without any `await` (so without the check) is refused too.
 */
function routeViolations(file: string, source: string): string[] {
  const problems: string[] = [];
  if (/^[ \t]*export\s+(\*|default\b|\{)/m.test(source)) {
    problems.push(`${file}: re-exports and default exports cannot be checked, declare the handler here`);
  }
  const chunks = source.split(/^[ \t]*export (?=(?:async\s+)?function\b|const\b|let\b|var\b)/m).slice(1);
  const handlers = chunks.flatMap((chunk) => {
    const name = /^(?:async\s+)?function\s+(\w+)|^(?:const|let|var)\s+(\w+)/.exec(chunk);
    const method = name?.[1] ?? name?.[2];
    return method !== undefined && HTTP_METHODS.has(method) ? [{ method, chunk }] : [];
  });
  if (handlers.length === 0) problems.push(`${file}: no exported HTTP method handler found`);
  for (const { method, chunk } of handlers) {
    const firstAwait = /\bawait\s+([A-Za-z_$][\w$.]*)/.exec(chunk)?.[1];
    if (firstAwait !== "requireStaffContext") {
      problems.push(`${file}: ${method}: first await is ${firstAwait ?? "missing"}, not requireStaffContext()`);
    }
  }
  return problems;
}

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
    // The team and notes actions exist today; seeing them proves the parser found real functions.
    expect(checked).toEqual(
      expect.arrayContaining([
        "rotateCodeAction", "removeMemberAction", "saveNoteAction", "uploadImageAction", "deleteImageAction",
      ]),
    );
  });

  it("allExportsOfAnActionsFileAreFunctions", () => {
    // A `export const fooAction = ...` would escape the function-based parser above.
    for (const file of actionFiles) {
      expect(readFileSync(file, "utf8"), rel(file)).not.toMatch(/^export (const|let|var|default)\b/m);
    }
  });

  const routes = walk(PANEL_DIR).filter((f) => f.endsWith("/route.ts"));

  it("findsTheRouteHandlers", () => {
    expect(routes.map(rel)).toEqual(
      expect.arrayContaining([
        "app/staff/(panel)/photos/[playerId]/[mealId]/route.ts",
        "app/staff/(panel)/files/[playerId]/[kind]/route.ts",
      ]),
    );
  });

  it("everyPanelRouteHandlerChecksTheSessionBeforeAnythingElse", () => {
    const problems = routes.flatMap((file) => routeViolations(rel(file), readFileSync(file, "utf8")));
    expect(problems).toEqual([]);
  });

  it("guardCoversRouteHandlers", () => {
    const guarded = `
      export const dynamic = "force-dynamic";
      export async function GET(_req: Request, { params }: Ctx) {
        const ctx = await requireStaffContext("status");
        return photo(ctx, await params);
      }`;
    expect(routeViolations("ok/route.ts", guarded)).toEqual([]);
    // several methods: each one is checked on its own
    expect(
      routeViolations("m/route.ts", `${guarded}\nexport async function POST() { await requireStaffContext(); }`),
    ).toEqual([]);

    const violations: Record<string, string> = {
      "no check at all": `export async function GET() { return new Response(await readPhoto()); }`,
      "params awaited first": `export async function GET(_r: Request, { params }: Ctx) {
        const p = await params;
        const ctx = await requireStaffContext();
      }`,
      "another call first": `export async function GET() { await rateLimit(); await requireStaffContext(); }`,
      "arrow function without any await": `export const GET = (req: Request) => handle(() => photo(req));`,
      "second method unguarded": `${guarded}\nexport async function DELETE() { await removeAll(); }`,
      "re-exported handler": `export { GET } from "./other";`,
      "default export": `export default async function handler() { await requireStaffContext(); }`,
      "no handler": `export const dynamic = "force-dynamic";`,
    };
    for (const [name, source] of Object.entries(violations)) {
      expect(routeViolations("bad/route.ts", source), name).not.toEqual([]);
    }
  });
});
