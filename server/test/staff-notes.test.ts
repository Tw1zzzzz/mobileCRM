import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { ProfileTab } from "@/components/staff/card/ProfileTab";
import { migrate } from "../db/migrate";
import { issueSession } from "../lib/auth/tokens";
import { ApiError } from "../lib/http";
import { deleteMe, getMe } from "../lib/me";
import { listSync } from "../lib/sync";
import { requireStaff, type StaffContext } from "../lib/staff/access";
import { loadCard, type CardData } from "../lib/staff/card";
import {
  IMAGE_KINDS,
  IMAGE_MAX_BYTES,
  NOTE_MAX_CHARS,
  deleteImage,
  loadProfileNotes,
  saveImage,
  saveNote,
  sniffImage,
} from "../lib/staff/notes";
import { removeMember } from "../lib/team/members";
import { resetDb, testDeps } from "./helpers";
import { seedCardTeam, type CardTeam } from "./staff-card-fixture";

const deps = testDeps();
const TZ = "Europe/Moscow";

// The Server Actions and the file route run against the test database with a cookie we control.
const session = vi.hoisted(() => ({ token: undefined as string | undefined }));
vi.mock("@/lib/deps", () => ({ defaultDeps: () => deps }));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => (name === "pulse_staff" && session.token ? { value: session.token } : undefined),
    set: () => undefined,
    delete: () => undefined,
  }),
  headers: async () => new Headers(),
}));

beforeEach(async () => {
  session.token = undefined;
  await resetDb(deps.sql);
});
afterAll(() => deps.sql.end());

// ---------------------------------------------------------------- helpers

const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52]);
const JPEG = Uint8Array.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0xff, 0xd9]);
// "RIFF" + length + "WEBP" + "VP8 "
const WEBP = Uint8Array.from([0x52, 0x49, 0x46, 0x46, 0x1a, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20]);
const WAVE = Uint8Array.from([0x52, 0x49, 0x46, 0x46, 0x1a, 0, 0, 0, 0x57, 0x41, 0x56, 0x45, 0x66, 0x6d, 0x74, 0x20]);
const GIF = new TextEncoder().encode("GIF89a\u0001\u0000\u0001\u0000");
const HTML = new TextEncoder().encode("<!doctype html><script>alert(1)</script>");
const SVG = new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>');

/** A PNG-looking buffer of exactly `size` bytes. */
function pngOfSize(size: number): Uint8Array {
  const bytes = new Uint8Array(size);
  bytes.set(PNG);
  return bytes;
}

function form(fields: Record<string, string | File>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

function fileOf(bytes: Uint8Array, name = "image.png", type = "image/png"): File {
  return new File([bytes as Uint8Array<ArrayBuffer>], name, { type });
}

async function redirectOf(run: () => Promise<unknown>): Promise<string> {
  try {
    await run();
  } catch (e) {
    const digest = (e as { digest?: unknown }).digest;
    if (typeof digest === "string" && digest.startsWith("NEXT_REDIRECT")) return digest.split(";")[2];
    throw e;
  }
  throw new Error("expected a redirect");
}

interface Seed extends CardTeam {
  /** A second staff member of the same team. */
  coach: string;
  coachCtx: StaffContext;
}

let extra = 0;
async function seed(): Promise<Seed> {
  const t = await seedCardTeam(deps);
  extra += 1;
  const now = deps.now();
  const [row] = await deps.sql<{ id: string }[]>`
    insert into users (email, nickname, password_hash, role, team_id, email_verified_at,
      accepted_terms_at, accepted_privacy_at, beta_terms_version, beta_terms_accepted_at, created_at, updated_at)
    values (${`coach${extra}@notes.gg`}, 'Coach-two', 'x', 'staff', ${t.ids.team}, ${now},
      ${now}, ${now}, '2026-10-26', ${now}, ${now}, ${now})
    returning id`;
  session.token = (await issueSession(deps, t.ids.staff)).refresh_token;
  return { ...t, coach: row.id, coachCtx: await requireStaff(deps, row.id) };
}

async function count(table: "staff_player_notes" | "staff_player_images", where = ""): Promise<number> {
  const [row] = await deps.sql.unsafe<{ n: string }[]>(`select count(*)::text as n from ${table} ${where}`);
  return Number(row.n);
}

async function storeImage(playerId: string, teamId: string, kind: string, bytes: Uint8Array): Promise<void> {
  await deps.sql`
    insert into staff_player_images (player_id, kind, team_id, content_type, bytes, updated_at)
    values (${playerId}, ${kind}, ${teamId}, 'image/png', ${bytes}, ${deps.now()})`;
}

async function storeNote(playerId: string, teamId: string, text: string): Promise<void> {
  await deps.sql`
    insert into staff_player_notes (player_id, team_id, communication, updated_at)
    values (${playerId}, ${teamId}, ${text}, ${deps.now()})`;
}

async function expectApiError(run: () => Promise<unknown>, status: number): Promise<void> {
  const error = await run().then(() => null, (e: unknown) => e);
  expect(error).toBeInstanceOf(ApiError);
  expect((error as ApiError).status).toBe(status);
}

const GENERIC_FAILURE = "/staff/players?notice=failed#notes";

// ---------------------------------------------------------------- migration

describe("migration 008", () => {
  it("createsTheTwoTablesWithTheirConstraints", async () => {
    const t = await seed();
    const columns = await deps.sql<{ table_name: string; column_name: string; is_nullable: string }[]>`
      select table_name, column_name, is_nullable from information_schema.columns
      where table_schema = 'public' and table_name in ('staff_player_notes', 'staff_player_images')`;
    const byTable = (name: string) => columns.filter((c) => c.table_name === name).map((c) => c.column_name).sort();
    expect(byTable("staff_player_notes")).toEqual(["communication", "player_id", "team_id", "updated_at", "updated_by"]);
    expect(byTable("staff_player_images")).toEqual(
      ["bytes", "content_type", "kind", "player_id", "team_id", "updated_at", "updated_by"],
    );
    // the kind check
    await expect(storeImage(t.ids.vega, t.ids.team, "avatar", PNG)).rejects.toThrow();
    // one note per player, one image per player and kind
    await storeNote(t.ids.vega, t.ids.team, "a");
    await expect(storeNote(t.ids.vega, t.ids.team, "b")).rejects.toThrow();
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    await expect(storeImage(t.ids.vega, t.ids.team, "scheme", PNG)).rejects.toThrow();
    await storeImage(t.ids.vega, t.ids.team, "roadmap", PNG);
  });

  it("runsTwiceWithoutChangingAnything", async () => {
    await migrate(deps.sql);
    await migrate(deps.sql);
    const tables = await deps.sql<{ tablename: string }[]>`
      select tablename from pg_tables where schemaname = 'public' and tablename like 'staff_player_%' order by 1`;
    expect(tables.map((r) => r.tablename)).toEqual(["staff_player_images", "staff_player_notes"]);
  });
});

// ---------------------------------------------------------------- notes

describe("notes", () => {
  it("noteSavedAndVisibleToOtherStaff", async () => {
    const t = await seed();
    await saveNote(deps, t.ctx, t.ids.vega, "Круг общения: IGL, Nova.\nОткрытый вопрос: роль.");
    const seen = await loadProfileNotes(deps, t.coachCtx, t.ids.vega, TZ);
    expect(seen.note?.text).toBe("Круг общения: IGL, Nova.\nОткрытый вопрос: роль.");
    expect(seen.note?.changed).toMatch(/^Изменено сегодня, \d\d:\d\d · Alpha-staff$/);
    // last write wins, and the label follows the last writer
    await saveNote(deps, t.coachCtx, t.ids.vega, "Новая версия");
    const again = await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ);
    expect(again.note?.text).toBe("Новая версия");
    expect(again.note?.changed).toMatch(/· Coach-two$/);
    expect(await count("staff_player_notes")).toBe(1);
    // another player has no note
    expect((await loadProfileNotes(deps, t.ctx, t.ids.nova, TZ)).note).toBeNull();
  });

  it("aNoteCanBeEmptiedAndKeepsItsLabelWithoutTheWriterAccount", async () => {
    const t = await seed();
    await saveNote(deps, t.coachCtx, t.ids.vega, "x");
    await saveNote(deps, t.coachCtx, t.ids.vega, "");
    expect((await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ)).note?.text).toBe("");
    // the author's account is gone (not a cascade: updated_by is only a label)
    await deps.sql`update staff_player_notes set updated_by = gen_random_uuid()`;
    const note = (await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ)).note;
    expect(note?.changed).toMatch(/^Изменено сегодня, \d\d:\d\d$/);
  });

  it("noteActionsRejectForeignPlayer", async () => {
    const t = await seed();
    const { saveNoteAction, uploadImageAction, deleteImageAction } = await import("../app/staff/actions");
    await storeImage(t.ids.outsider, (await teamOf(t.ids.outsider)) as string, "scheme", PNG);
    const foreign = ["outsider", "solo", "staff", "loneStaff"] as const;
    const ids = [...foreign.map((k) => t.ids[k]), "not-a-uuid", "00000000-0000-4000-8000-000000000000", "' or 1=1 --"];
    for (const player of ids) {
      expect(await redirectOf(() => saveNoteAction(form({ player, text: "pwned" })))).toBe(GENERIC_FAILURE);
      expect(
        await redirectOf(() => uploadImageAction(form({ player, kind: "scheme", file: fileOf(PNG) }))),
      ).toBe(GENERIC_FAILURE);
      expect(await redirectOf(() => deleteImageAction(form({ player, kind: "scheme" })))).toBe(GENERIC_FAILURE);
    }
    expect(await count("staff_player_notes")).toBe(0);
    // the other team's image is neither replaced nor deleted
    expect(await count("staff_player_images")).toBe(1);
    const [kept] = await deps.sql<{ bytes: Buffer }[]>`select bytes from staff_player_images`;
    expect(new Uint8Array(kept.bytes)).toEqual(PNG);
    // the library functions say the same thing
    await expectApiError(() => saveNote(deps, t.ctx, t.ids.outsider, "x"), 404);
    await expectApiError(() => saveImage(deps, t.ctx, t.ids.outsider, "scheme", PNG), 404);
    await expectApiError(() => deleteImage(deps, t.ctx, t.ids.outsider, "scheme"), 404);
    await expectApiError(() => saveNote(deps, t.ctx, "not-a-uuid", "x"), 404);
  });

  it("theTeamAlwaysComesFromTheSession", async () => {
    const t = await seed();
    const { saveNoteAction } = await import("../app/staff/actions");
    const other = (await teamOf(t.ids.otherStaff)) as string;
    const href = await redirectOf(() =>
      saveNoteAction(form({ player: t.ids.vega, text: "mine", teamId: other, team_id: other, next: "https://evil.example" })),
    );
    expect(href).toBe(`/staff/players?player=${t.ids.vega}&notice=note-saved#notes`);
    const [row] = await deps.sql<{ team_id: string }[]>`select team_id from staff_player_notes`;
    expect(row.team_id).toBe(t.ids.team);
  });

  it("noteTooLongRejected", async () => {
    const t = await seed();
    const { saveNoteAction } = await import("../app/staff/actions");
    await expectApiError(() => saveNote(deps, t.ctx, t.ids.vega, "я".repeat(NOTE_MAX_CHARS + 1)), 422);
    expect(await count("staff_player_notes")).toBe(0);
    await saveNote(deps, t.ctx, t.ids.vega, "я".repeat(NOTE_MAX_CHARS));
    // browsers send CRLF; the limit counts the line break once, like the textarea's maxlength
    await saveNote(deps, t.ctx, t.ids.vega, "a\r\n".repeat(2500));
    expect((await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ)).note?.text).toBe("a\n".repeat(2500));
    // a NUL cannot be stored in Postgres text: dropped, not a crash
    await saveNote(deps, t.ctx, t.ids.vega, "a\u0000b");
    expect((await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ)).note?.text).toBe("ab");
    // through the action: the old note stays and the page says why
    const href = await redirectOf(() => saveNoteAction(form({ player: t.ids.vega, text: "z".repeat(NOTE_MAX_CHARS + 1) })));
    expect(href).toBe(`/staff/players?player=${t.ids.vega}&notice=note-too-long#notes`);
    expect((await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ)).note?.text).toBe("ab");
  });

  it("theActionKeepsTheCardSelectionButOnlyValidValues", async () => {
    const t = await seed();
    const { saveNoteAction } = await import("../app/staff/actions");
    const href = await redirectOf(() =>
      saveNoteAction(form({ player: t.ids.vega, text: "x", period: "90", type: "cog", src: "javascript:alert(1)", day: "garbage" })),
    );
    expect(href).toBe(`/staff/players?player=${t.ids.vega}&period=90&type=cog&notice=note-saved#notes`);
  });
});

async function teamOf(userId: string): Promise<string | null> {
  const [row] = await deps.sql<{ team_id: string | null }[]>`select team_id from users where id = ${userId}`;
  return row.team_id;
}

// ---------------------------------------------------------------- images

describe("images", () => {
  it("sniffsByMagicBytesOnly", () => {
    expect(sniffImage(PNG)).toBe("image/png");
    expect(sniffImage(JPEG)).toBe("image/jpeg");
    expect(sniffImage(WEBP)).toBe("image/webp");
    for (const bad of [HTML, SVG, GIF, WAVE, new Uint8Array(0), PNG.slice(0, 7), JPEG.slice(0, 2), WEBP.slice(0, 11)]) {
      expect(sniffImage(bad)).toBeNull();
    }
  });

  it("imageUploadSniffsAndLimits", async () => {
    const t = await seed();
    const { uploadImageAction } = await import("../app/staff/actions");
    const upload = (file: File | string, kind = "scheme") =>
      redirectOf(() => uploadImageAction(form({ player: t.ids.vega, kind, file })));
    const back = (notice: string) => `/staff/players?player=${t.ids.vega}&notice=${notice}#notes`;

    // an HTML page named .png (and declared image/png), SVG, GIF, WAVE: rejected, nothing stored
    expect(await upload(fileOf(HTML, "x.png", "image/png"))).toBe(back("scheme-type"));
    expect(await upload(fileOf(SVG, "x.svg", "image/svg+xml"))).toBe(back("scheme-type"));
    expect(await upload(fileOf(SVG, "x.png", "image/png"))).toBe(back("scheme-type"));
    expect(await upload(fileOf(GIF, "x.png", "image/png"))).toBe(back("scheme-type"));
    expect(await upload(fileOf(WAVE, "x.webp", "image/webp"))).toBe(back("scheme-type"));
    // 2 000 001 bytes, empty, no file at all, a text field instead of a file
    expect(await upload(fileOf(pngOfSize(IMAGE_MAX_BYTES + 1)))).toBe(back("scheme-size"));
    expect(await upload(fileOf(new Uint8Array(0)))).toBe(back("scheme-empty"));
    expect(await upload("not a file")).toBe(back("scheme-empty"));
    expect(await redirectOf(() => uploadImageAction(form({ player: t.ids.vega, kind: "scheme" })))).toBe(back("scheme-empty"));
    expect(await count("staff_player_images")).toBe(0);

    // real images are stored under the SNIFFED type, whatever the client claims
    expect(await upload(fileOf(PNG, "a.svg", "image/svg+xml"))).toBe(back("scheme-saved"));
    expect(await upload(fileOf(JPEG, "b.png", "text/html"), "roadmap")).toBe(back("roadmap-saved"));
    expect(await upload(fileOf(WEBP, "c.jpg", "image/jpeg"), "mindmap")).toBe(back("mindmap-saved"));
    const rows = await deps.sql<{ kind: string; content_type: string; team_id: string; updated_by: string }[]>`
      select kind, content_type, team_id, updated_by from staff_player_images order by kind`;
    expect(rows.map((r) => [r.kind, r.content_type])).toEqual([
      ["mindmap", "image/webp"],
      ["roadmap", "image/jpeg"],
      ["scheme", "image/png"],
    ]);
    expect(rows.every((r) => r.team_id === t.ids.team && r.updated_by === t.ids.staff)).toBe(true);

    // exactly the limit is fine; replacing keeps one row per kind
    expect(await upload(fileOf(pngOfSize(IMAGE_MAX_BYTES)))).toBe(back("scheme-saved"));
    expect(await count("staff_player_images")).toBe(3);
    const [big] = await deps.sql<{ n: number }[]>`select length(bytes) as n from staff_player_images where kind = 'scheme'`;
    expect(big.n).toBe(IMAGE_MAX_BYTES);
  });

  it("aBadKindChangesNothing", async () => {
    const t = await seed();
    const { uploadImageAction, deleteImageAction } = await import("../app/staff/actions");
    for (const kind of ["avatar", "", "SCHEME", "scheme ", "../scheme", "scheme';--"]) {
      expect(await redirectOf(() => uploadImageAction(form({ player: t.ids.vega, kind, file: fileOf(PNG) })))).toBe(GENERIC_FAILURE);
      expect(await redirectOf(() => deleteImageAction(form({ player: t.ids.vega, kind })))).toBe(GENERIC_FAILURE);
    }
    await expectApiError(() => saveImage(deps, t.ctx, t.ids.vega, "avatar" as never, PNG), 422);
    expect(await count("staff_player_images")).toBe(0);
    expect(IMAGE_KINDS).toEqual(["scheme", "roadmap", "mindmap"]);
  });

  it("deleteRemovesOnlyThatImage", async () => {
    const t = await seed();
    const { deleteImageAction } = await import("../app/staff/actions");
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    await storeImage(t.ids.vega, t.ids.team, "roadmap", PNG);
    await storeImage(t.ids.nova, t.ids.team, "scheme", PNG);
    expect(await redirectOf(() => deleteImageAction(form({ player: t.ids.vega, kind: "scheme" })))).toBe(
      `/staff/players?player=${t.ids.vega}&notice=scheme-deleted#notes`,
    );
    const left = await deps.sql<{ player_id: string; kind: string }[]>`select player_id, kind from staff_player_images order by kind`;
    expect(left.map((r) => [r.player_id, r.kind])).toEqual([[t.ids.vega, "roadmap"], [t.ids.nova, "scheme"]]);
  });

  it("theCardKnowsWhichImagesExistWithoutLoadingTheirBytes", async () => {
    const t = await seed();
    await storeImage(t.ids.vega, t.ids.team, "roadmap", PNG);
    const profile = await loadProfileNotes(deps, t.ctx, t.ids.vega, TZ);
    expect(profile.images.scheme).toBeNull();
    expect(profile.images.mindmap).toBeNull();
    expect(typeof profile.images.roadmap?.version).toBe("number");
    expect(JSON.stringify(profile)).not.toContain("bytes");
  });
});

// ---------------------------------------------------------------- the file route

describe("file route", () => {
  async function get(playerId: string, kind: string): Promise<Response> {
    const { GET } = await import("../app/staff/(panel)/files/[playerId]/[kind]/route");
    return GET(new Request(`http://localhost/staff/files/${playerId}/${kind}`), {
      params: Promise.resolve({ playerId, kind }),
    });
  }

  async function observable(res: Response): Promise<unknown> {
    return {
      status: res.status,
      type: res.headers.get("content-type"),
      cache: res.headers.get("cache-control"),
      body: (await res.arrayBuffer()).byteLength,
    };
  }

  it("imageRouteChecksRoster", async () => {
    const t = await seed();
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    await deps.sql`update staff_player_images set content_type = 'image/png'`;
    const otherTeam = (await teamOf(t.ids.outsider)) as string;
    await storeImage(t.ids.outsider, otherTeam, "scheme", JPEG);
    await storeImage(t.ids.solo, otherTeam, "scheme", JPEG);

    const ok = await get(t.ids.vega, "scheme");
    expect(ok.status).toBe(200);
    expect(ok.headers.get("content-type")).toBe("image/png");
    expect(ok.headers.get("x-content-type-options")).toBe("nosniff");
    expect(ok.headers.get("cache-control")).toBe("private, no-store");
    expect(ok.headers.get("content-security-policy")).toContain("default-src 'none'");
    expect(new Uint8Array(await ok.arrayBuffer())).toEqual(PNG);

    // not an existing image: the answer every refusal must look like
    const none = await observable(await get(t.ids.vega, "roadmap"));
    expect(none).toMatchObject({ status: 404, body: 0 });
    for (const [player, kind] of [
      [t.ids.outsider, "scheme"], // another team's player, with an image
      [t.ids.solo, "scheme"],
      [t.ids.staff, "scheme"],
      [t.ids.nova, "scheme"], // a player without that image
      ["not-a-uuid", "scheme"],
      ["00000000-0000-4000-8000-000000000000", "scheme"],
      [t.ids.vega, "avatar"],
      [t.ids.vega, "../scheme"],
      [t.ids.vega, "scheme.png"],
      ["' or 1=1 --", "scheme"],
    ] as const) {
      expect(await observable(await get(player, kind)), `${player}/${kind}`).toEqual(none);
    }
  });

  it("anotherTeamsStaffAndPlayersAreTurnedAway", async () => {
    const t = await seed();
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    session.token = (await issueSession(deps, t.ids.otherStaff)).refresh_token;
    expect((await get(t.ids.vega, "scheme")).status).toBe(404);
    session.token = (await issueSession(deps, t.ids.vega)).refresh_token; // a player of the team
    const asPlayer = await get(t.ids.vega, "scheme");
    expect(asPlayer.status).toBe(403);
    expect((await asPlayer.arrayBuffer()).byteLength).toBe(0);
    session.token = (await issueSession(deps, t.ids.solo)).refresh_token;
    expect((await get(t.ids.vega, "scheme")).status).toBe(403);
    session.token = undefined;
    const anonymous = await get(t.ids.vega, "scheme");
    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get("location")).toBeNull();
  });

  it("aRemovedPlayerNoLongerHasAnImageRoute", async () => {
    const t = await seed();
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.vega);
    expect((await get(t.ids.vega, "scheme")).status).toBe(404);
  });
});

// ---------------------------------------------------------------- players, deletion, guard

describe("players cannot read notes", () => {
  const ROOT = join(__dirname, "..");
  const walk = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)]));

  it("playersCannotReadNotes", async () => {
    // 1. only the staff module and the removal code know the tables
    const sources = [...walk(join(ROOT, "app")), ...walk(join(ROOT, "lib")), ...walk(join(ROOT, "components"))].filter((f) =>
      /\.tsx?$/.test(f),
    );
    const users = sources.filter((f) => /staff_player_(notes|images)/.test(readFileSync(f, "utf8"))).map((f) => relative(ROOT, f));
    expect(users.sort()).toEqual(["lib/staff/notes.ts", "lib/team/members.ts"]);
    // 2. no route of the player API reaches into the staff modules
    const api = sources.filter((f) => relative(ROOT, f).startsWith(join("app", "v1")));
    expect(api.length).toBeGreaterThan(5);
    for (const file of api) expect(readFileSync(file, "utf8"), relative(ROOT, file)).not.toMatch(/staff\/|\/staff|notes/);

    // 3. what the player's own endpoints return
    const t = await seed();
    const secret = "СЕКРЕТНАЯ ЗАМЕТКА О ИГРОКЕ";
    await saveNote(deps, t.ctx, t.ids.vega, secret);
    await saveImage(deps, t.ctx, t.ids.vega, "scheme", PNG);
    const pair = await issueSession(deps, t.ids.vega);
    const authed = (url: string) =>
      new Request(url, { headers: { authorization: `Bearer ${pair.access_token}` } });
    const me = await getMe(authed("http://localhost/v1/me"), deps);
    const sync = await listSync(authed("http://localhost/v1/sync"), deps);
    for (const res of [me, sync]) {
      expect(res.status).toBe(200);
      const body = await res.text();
      expect(body).not.toContain(secret);
      expect(body).not.toMatch(/communication|staff_player|roadmap|mindmap/);
    }
  });
});

describe("deletion", () => {
  it("removeMemberDeletesNotesAndImages", async () => {
    const t = await seed();
    for (const id of [t.ids.vega, t.ids.nova]) {
      await storeNote(id, t.ids.team, `note ${id}`);
      await storeImage(id, t.ids.team, "scheme", PNG);
      await storeImage(id, t.ids.team, "mindmap", PNG);
    }
    await removeMember(deps, t.ids.team, t.ids.staff, t.ids.vega);
    expect(await count("staff_player_notes", `where player_id = '${t.ids.vega}'`)).toBe(0);
    expect(await count("staff_player_images", `where player_id = '${t.ids.vega}'`)).toBe(0);
    // the other player is untouched
    expect(await count("staff_player_notes", `where player_id = '${t.ids.nova}'`)).toBe(1);
    expect(await count("staff_player_images", `where player_id = '${t.ids.nova}'`)).toBe(2);
    // a staff member leaving (the successor takes over) is harmless
    await removeMember(deps, t.ids.team, t.ids.staff, t.coach);
    expect(await count("staff_player_notes")).toBe(1);
  });

  it("aFailedRemovalKeepsTheNotes", async () => {
    const t = await seed();
    await storeNote(t.ids.staff, t.ids.team, "n");
    await deps.sql`delete from users where id = ${t.coach}`;
    // the last staff member cannot be removed: nothing is deleted
    await expectApiError(() => removeMember(deps, t.ids.team, t.ids.staff, t.ids.staff), 422);
    expect(await count("staff_player_notes")).toBe(1);
  });

  it("accountDeletionDeletesNotes", async () => {
    const t = await seed();
    await storeNote(t.ids.vega, t.ids.team, "n");
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    await storeNote(t.ids.nova, t.ids.team, "n");
    const pair = await issueSession(deps, t.ids.vega);
    const res = await deleteMe(new Request("http://localhost/v1/me", { method: "DELETE", headers: { authorization: `Bearer ${pair.access_token}` } }), deps);
    expect(res.status).toBe(200);
    expect(await count("staff_player_notes")).toBe(1);
    expect(await count("staff_player_images")).toBe(0);
  });

  it("teamDismantlingDeletesNotes", async () => {
    const t = await seed();
    await storeNote(t.ids.vega, t.ids.team, "n");
    await storeImage(t.ids.nova, t.ids.team, "roadmap", PNG);
    // another team's note must survive
    const bravo = (await teamOf(t.ids.otherStaff)) as string;
    await storeNote(t.ids.outsider, bravo, "theirs");
    // the coach leaves first, then the last staff member deletes the account: the team is dismantled
    await deps.sql`delete from users where id = ${t.coach}`;
    const pair = await issueSession(deps, t.ids.staff);
    const res = await deleteMe(new Request("http://localhost/v1/me", { method: "DELETE", headers: { authorization: `Bearer ${pair.access_token}` } }), deps);
    expect(res.status).toBe(200);
    expect(await count("staff_player_notes", `where team_id = '${t.ids.team}'`)).toBe(0);
    expect(await count("staff_player_images")).toBe(0);
    expect(await count("staff_player_notes")).toBe(1);
    // the former players are solo players now and are nobody's roster
    expect(await teamOf(t.ids.vega)).toBeNull();
  });
});

describe("actions require staff context", () => {
  it("actionsRequireStaffContext", async () => {
    const t = await seed();
    const { saveNoteAction, uploadImageAction, deleteImageAction } = await import("../app/staff/actions");
    await storeImage(t.ids.vega, t.ids.team, "scheme", PNG);
    const attempt = async () => [
      await redirectOf(() => saveNoteAction(form({ player: t.ids.vega, text: "x" }))),
      await redirectOf(() => uploadImageAction(form({ player: t.ids.vega, kind: "roadmap", file: fileOf(PNG) }))),
      await redirectOf(() => deleteImageAction(form({ player: t.ids.vega, kind: "scheme" }))),
    ];
    session.token = undefined;
    expect(await attempt()).toEqual(["/staff/login", "/staff/login", "/staff/login"]);
    for (const user of [t.ids.vega, t.ids.solo]) {
      session.token = (await issueSession(deps, user)).refresh_token;
      expect(await attempt()).toEqual(["/staff/no-access", "/staff/no-access", "/staff/no-access"]);
    }
    expect(await count("staff_player_notes")).toBe(0);
    expect(await count("staff_player_images")).toBe(1);
  });
});

// ---------------------------------------------------------------- the card

function cardOf(data: CardData) {
  if (data.kind !== "card") throw new Error("expected a card");
  return data;
}

describe("profile tab", () => {
  const html = (card: ReturnType<typeof cardOf>): string =>
    renderToStaticMarkup(createElement(ProfileTab, { archetype: card.archetype, profile: card.profile as never, params: card.params }));
  const words = (markup: string): string => markup.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");

  it("loadCardLoadsNotesOnlyForTheProfileTab", async () => {
    const t = await seed();
    const profile = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega }, TZ));
    expect(profile.profile).not.toBeNull();
    const sleep = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, tab: "sleep" }, TZ));
    expect(sleep.profile).toBeNull();
  });

  it("emptyProfileShowsTheBoardCopy", async () => {
    const t = await seed();
    const markup = html(cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega }, TZ)));
    const text = words(markup);
    for (const copy of [
      "Коммуникативная линия",
      "Круг общения в команде, статус общения, открытые вопросы — заметки видит только staff",
      "Заметки о коммуникации",
      "Сохранить",
      "Схема общения",
      "Загрузите изображение схемы",
      "Roadmap",
      "План развития игрока",
      "Mindmap",
      "Карта мышления",
    ]) {
      expect(text, copy).toContain(copy);
    }
    expect(markup.match(/>Загрузить</g)).toHaveLength(3);
    expect(markup).toContain('<textarea');
    expect(markup).toContain(`maxLength="${NOTE_MAX_CHARS}"`);
    expect(markup).not.toContain("<img");
    expect(text).not.toContain("Изменено");
    expect(text).not.toContain("Заменить");
    expect(text).not.toContain("Удалить");
    // every control posts the roster-checked player and the card selection
    expect(markup).toContain(`name="player" value="${t.ids.vega}"`);
    expect(markup).not.toContain("dangerouslySetInnerHTML");
  });

  it("savedNoteAndImagesAreShown", async () => {
    const t = await seed();
    const hostile = '</textarea><script>alert(1)</script><img src=x onerror=alert(1)>';
    await saveNote(deps, t.ctx, t.ids.vega, hostile);
    await saveImage(deps, t.ctx, t.ids.vega, "scheme", PNG);
    await saveImage(deps, t.ctx, t.ids.vega, "mindmap", JPEG);
    const card = cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, notice: "note-saved" }, TZ));
    const markup = html(card);
    const text = words(markup);
    // (React adds its own form-replay <script> to static markup; the note's text must be inert)
    expect(markup).not.toContain("<script>alert");
    expect(markup).not.toContain("<img src=x");
    expect(markup).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(text).toMatch(/Изменено сегодня, \d\d:\d\d · Alpha-staff/);
    expect(text).toContain("Сохранено");
    const version = card.profile?.images.scheme?.version;
    expect(markup).toContain(`<img src="/staff/files/${t.ids.vega}/scheme?v=${version}"`);
    expect(markup).toContain(`/staff/files/${t.ids.vega}/mindmap?v=`);
    expect(markup).not.toContain(`/staff/files/${t.ids.vega}/roadmap`);
    expect(markup.match(/>Заменить</g)).toHaveLength(2);
    expect(markup.match(/>Удалить</g)).toHaveLength(2);
    expect(markup.match(/>Загрузить</g)).toHaveLength(1); // roadmap has none yet
    expect(text).toContain("План развития игрока");
  });

  it("noticesAreWhitelistedTexts", async () => {
    const t = await seed();
    const show = async (notice: string) =>
      words(html(cardOf(await loadCard(deps, t.ctx, { player: t.ids.vega, notice }, TZ))));
    expect(await show("scheme-size")).toContain("Файл больше 2 МБ");
    expect(await show("note-too-long")).toContain("5000");
    expect(await show("roadmap-type")).toContain("PNG, JPEG или WebP");
    expect(await show("failed")).toContain("Не удалось");
    const unknown = await show("<script>alert(1)</script>");
    expect(unknown).not.toContain("alert");
    expect(unknown).not.toContain("Сохранено");
  });
});
