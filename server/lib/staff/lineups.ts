import type { Sql, TransactionSql } from "postgres";
import { z } from "zod";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import type { StaffContext } from "@/lib/staff/access";
import { STAFF_TIME_ZONE, dayOf, type Day } from "@/lib/staff/metrics";

export const LINEUP_MAPS = ["mirage", "inferno", "nuke", "ancient", "anubis", "dust2", "train"] as const;
export const LINEUP_TYPES = ["smoke", "flash", "molotov", "grenade", "script", "note"] as const;
export const LINEUP_SIDES = ["t", "ct"] as const;
export type LineupMap = (typeof LINEUP_MAPS)[number];
export type StaffLineupType = (typeof LINEUP_TYPES)[number];
export type LineupSide = (typeof LINEUP_SIDES)[number];

type Queryable = Sql | TransactionSql;
const MAX_LINEUPS_REQUESTS_PER_MINUTE = 120;
const MAX_LINEUP_BYTES = 64_000;

export interface StaffLineup {
  id: string;
  title: string;
  map: LineupMap;
  side: LineupSide;
  type: StaffLineupType;
  details: string;
  videoUrl: string | null;
  author: string;
  createdAt: Date;
  favorite: boolean;
}

export type LineupFilter = {
  q: string;
  map: LineupMap | "all";
  type: StaffLineupType | "all";
  side: LineupSide | "all";
  favorites: boolean;
  entry: string | null;
};

export type LineupError = "invalid" | "not-found" | "save-failed";
/** The create / edit dialog the URL asks for: `?form=lineup`, `?form=script` or `?form=edit` (the selected entry). */
export type LineupFormMode = "lineup" | "script" | "edit";
const FORM_MODES: readonly LineupFormMode[] = ["lineup", "script", "edit"];

/** The four counters of the page: the whole team's base, and this staff member's favourites. */
export interface LineupStats {
  total: number;
  /** Smoke, flash, molotov and HE entries. */
  lineups: number;
  scripts: number;
  favorites: number;
}

export type LineupNotice = "created" | "saved" | "deleted";

export interface StaffLineupsPage {
  filters: LineupFilter;
  entries: StaffLineup[];
  selected: StaffLineup | null;
  totalForTeam: number;
  stats: LineupStats;
  /** Entries per map under the other filters (the map chip's number); `all` is their sum. */
  mapCounts: Record<LineupMap | "all", number>;
  /** The current calendar day in the staff time zone, to word «сегодня» / «вчера». */
  today: Day;
  form: LineupFormMode | null;
  hasMore: boolean;
  error: LineupError | null;
  notice: LineupNotice | null;
}

const ERRORS: readonly LineupError[] = ["invalid", "not-found", "save-failed"];
const NOTICES: readonly LineupNotice[] = ["created", "saved", "deleted"];
const first = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value;
const enumValue = <T extends readonly string[]>(raw: string | undefined, values: T): T[number] | "all" =>
  raw && (values as readonly string[]).includes(raw) ? raw as T[number] : "all";

function validVideoUrl(value: string): boolean {
  if (!value) return true;
  try {
    const url = new URL(value);
    return (url.protocol === "https:" || url.protocol === "http:") && !!url.hostname && !url.username && !url.password;
  } catch { return false; }
}

export async function loadStaffLineups(
  deps: Deps,
  ctx: StaffContext,
  params: { q?: string | string[]; map?: string | string[]; type?: string | string[]; side?: string | string[]; fav?: string | string[]; entry?: string | string[]; form?: string | string[]; error?: string | string[]; notice?: string | string[] },
): Promise<StaffLineupsPage> {
  const rawQ = first(params.q) ?? "";
  const filters: LineupFilter = {
    q: rawQ.trim().slice(0, 100),
    map: enumValue(first(params.map), LINEUP_MAPS),
    type: enumValue(first(params.type), LINEUP_TYPES),
    side: enumValue(first(params.side), LINEUP_SIDES),
    favorites: first(params.fav) === "1",
    entry: first(params.entry) ?? null,
  };
  // Every filter but the map: the map chips count what each map would show under the rest.
  const otherFilters = deps.sql`
    l.team_id = ${ctx.teamId}
      and (${filters.type} = 'all' or l.type = ${filters.type})
      and (${filters.side} = 'all' or l.side = ${filters.side})
      and (${filters.favorites} = false or f.lineup_id is not null)
      and (${filters.q} = '' or strpos(lower(l.title || ' ' || l.map || ' ' || l.details), lower(${filters.q})) > 0)`;
  const [[stats], mapRows, rows] = await Promise.all([
    deps.sql<LineupStats[]>`
      select count(*)::int as total,
        (count(*) filter (where l.type in ('smoke', 'flash', 'molotov', 'grenade')))::int as lineups,
        (count(*) filter (where l.type = 'script'))::int as scripts,
        (count(*) filter (where f.lineup_id is not null))::int as favorites
      from staff_lineups l
      left join staff_lineup_favorites f on f.lineup_id = l.id and f.staff_user_id = ${ctx.userId}
      where l.team_id = ${ctx.teamId}`,
    deps.sql<{ map: LineupMap; count: number }[]>`
      select l.map, count(*)::int as count
      from staff_lineups l
      left join staff_lineup_favorites f on f.lineup_id = l.id and f.staff_user_id = ${ctx.userId}
      where ${otherFilters}
      group by l.map`,
    deps.sql<{
      id: string; title: string; map: LineupMap; side: LineupSide; type: StaffLineupType; details: string;
      video_url: string | null; author: string | null; created_at: Date; favorite: boolean;
    }[]>`
      select l.id, l.title, l.map, l.side, l.type, l.details, l.video_url,
        coalesce(u.nickname, 'Бывший участник') as author, l.created_at,
        (f.lineup_id is not null) as favorite
      from staff_lineups l
      left join users u on u.id = l.created_by
      left join staff_lineup_favorites f on f.lineup_id = l.id and f.staff_user_id = ${ctx.userId}
      where ${otherFilters} and (${filters.map} = 'all' or l.map = ${filters.map})
      order by l.updated_at desc, l.id
      limit 501`,
  ]);
  const mapCounts = Object.fromEntries([...LINEUP_MAPS.map((map) => [map, 0]), ["all", 0]]) as Record<LineupMap | "all", number>;
  for (const row of mapRows) {
    mapCounts[row.map] = row.count;
    mapCounts.all += row.count;
  }
  const entries = rows.slice(0, 500).map((row) => ({
    id: row.id, title: row.title, map: row.map, side: row.side, type: row.type, details: row.details,
    videoUrl: row.video_url, author: row.author ?? "Бывший участник", createdAt: row.created_at, favorite: row.favorite,
  }));
  const selected = (filters.entry ? entries.find((entry) => entry.id === filters.entry) : null) ?? entries[0] ?? null;
  const rawError = first(params.error);
  const rawNotice = first(params.notice);
  const rawForm = first(params.form);
  const form = FORM_MODES.find((mode) => mode === rawForm && (mode !== "edit" || selected !== null)) ?? null;
  return {
    filters, entries, selected, totalForTeam: stats.total, stats, mapCounts, today: dayOf(deps.now(), STAFF_TIME_ZONE), form,
    hasMore: rows.length > 500,
    error: ERRORS.find((value) => value === rawError) ?? null,
    notice: NOTICES.find((value) => value === rawNotice) ?? null,
  };
}

const FormFields = z.object({
  id: z.string().uuid().optional().or(z.literal("")),
  title: z.string().trim().min(1).max(120),
  map: z.enum(LINEUP_MAPS),
  side: z.enum(LINEUP_SIDES),
  type: z.enum(LINEUP_TYPES),
  details: z.string().trim().max(4000),
  videoUrl: z.string().trim().max(2048),
});

function field(form: FormData, name: string): string {
  const value = form.get(name);
  return typeof value === "string" ? value : "";
}

export async function saveStaffLineup(deps: Deps, ctx: StaffContext, form: FormData): Promise<{ id: string; created: boolean } | null> {
  const type = field(form, "type");
  const parsed = FormFields.safeParse({
    // A note is not thrown from a side: the form may leave it out.
    id: field(form, "id"), title: field(form, "title"), map: field(form, "map"), side: field(form, "side") || (type === "note" ? "t" : ""),
    type, details: field(form, "details"), videoUrl: field(form, "videoUrl"),
  });
  if (!parsed.success || !validVideoUrl(parsed.data.videoUrl)) return null;
  const input = parsed.data;
  const videoUrl = input.videoUrl || null;
  const now = deps.now();
  if (input.id) {
    const rows = await deps.sql<{ id: string }[]>`
      update staff_lineups set title = ${input.title}, map = ${input.map}, side = ${input.side}, type = ${input.type},
        details = ${input.details}, video_url = ${videoUrl}, updated_at = ${now}
      where id = ${input.id} and team_id = ${ctx.teamId} returning id`;
    return rows.length ? { id: rows[0].id, created: false } : null;
  }
  const rows = await deps.sql<{ id: string }[]>`
    insert into staff_lineups (team_id, created_by, title, map, side, type, details, video_url, created_at, updated_at)
    values (${ctx.teamId}, ${ctx.userId}, ${input.title}, ${input.map}, ${input.side}, ${input.type}, ${input.details}, ${videoUrl}, ${now}, ${now})
    returning id`;
  return rows.length ? { id: rows[0].id, created: true } : null;
}

export async function deleteStaffLineup(deps: Deps, ctx: StaffContext, id: string): Promise<boolean> {
  const rows = await deps.sql<{ id: string }[]>`
    delete from staff_lineups where id = ${id} and team_id = ${ctx.teamId} returning id`;
  return rows.length > 0;
}

export async function toggleStaffLineupFavorite(deps: Deps, ctx: StaffContext, id: string): Promise<boolean> {
  return deps.sql.begin(async (tx) => {
    const rows = await tx<{ id: string }[]>`select id from staff_lineups where id = ${id} and team_id = ${ctx.teamId} for update`;
    if (!rows.length) return false;
    const removed = await tx<{ lineup_id: string }[]>`
      delete from staff_lineup_favorites where lineup_id = ${id} and staff_user_id = ${ctx.userId} returning lineup_id`;
    if (!removed.length) {
      await tx`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${id}, ${ctx.userId}) on conflict do nothing`;
    }
    return true;
  });
}

/** Apps print ids in capitals (Swift heritage); the database stores and answers in lower case. */
const AnyCaseUuid = z.string().uuid().transform((value) => value.toLowerCase());

/**
 * The app sends the API's snake_case keys (`video_url`, `is_favorite`); an earlier server read camelCase ones.
 * Both are accepted and the answer is snake_case, which is what the app decodes.
 */
function withSnakeCaseAliases(raw: unknown): unknown {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) return raw;
  const body = raw as Record<string, unknown>;
  const variations = Array.isArray(body.variations)
    ? body.variations.map((variation) => typeof variation === "object" && variation !== null && !Array.isArray(variation)
        ? { ...variation, videoURL: (variation as Record<string, unknown>).videoURL ?? (variation as Record<string, unknown>).video_url }
        : variation)
    : body.variations;
  return { ...body, videoURL: body.videoURL ?? body.video_url, isFavorite: body.isFavorite ?? body.is_favorite, variations };
}

const VariationInput = z.object({
  id: AnyCaseUuid.optional(),
  position: z.string().trim().max(240),
  technique: z.enum(["standard", "jump", "run", "runJump", "walk", "rightClick"]),
  aim: z.string().trim().max(400),
  videoURL: z.string().trim().max(2048).nullable().optional(),
});
const TeamLineupInput = z.preprocess(withSnakeCaseAliases, z.object({
  id: AnyCaseUuid,
  title: z.string().trim().min(1).max(120),
  map: z.enum(LINEUP_MAPS),
  side: z.enum(LINEUP_SIDES),
  type: z.enum(LINEUP_TYPES),
  details: z.string().trim().max(4000),
  videoURL: z.string().trim().max(2048).nullable().optional(),
  visibility: z.literal("team"),
  isFavorite: z.boolean().default(false),
  variations: z.array(VariationInput).max(20).default([]),
}));
const TeamLineupFavoriteInput = z.preprocess(withSnakeCaseAliases, z.object({ isFavorite: z.boolean() }));

interface TeamMemberContext extends StaffContext { role: "staff" | "teamPlayer"; nickname: string }

async function teamMemberContext(req: Request, deps: Deps): Promise<TeamMemberContext> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, `lineups:${userId}`, MAX_LINEUPS_REQUESTS_PER_MINUTE, 60_000);
  const [row] = await deps.sql<{ team_id: string | null; team_name: string | null; role: string; nickname: string; email_verified_at: Date | null }[]>`
    select u.team_id, t.name as team_name, u.role, u.nickname, u.email_verified_at
    from users u left join teams t on t.id = u.team_id where u.id = ${userId}`;
  if (!row || !row.team_id || !row.team_name || row.email_verified_at === null || (row.role !== "staff" && row.role !== "teamPlayer")) {
    throw new ApiError("forbidden", "Team members only", 403);
  }
  return { userId, teamId: row.team_id, teamName: row.team_name, role: row.role, nickname: row.nickname };
}

async function teamLineupRows(db: Queryable, ctx: TeamMemberContext, id?: string) {
  return db<{
    id: string; title: string; map: LineupMap; side: LineupSide; type: string; details: string; video_url: string | null;
    variations: unknown; author: string; created_at: Date; favorite: boolean;
  }[]>`
    select l.id, l.title, l.map, l.side, l.type, l.details, l.video_url, l.variations,
      coalesce(u.nickname, 'Бывший участник') as author, l.created_at, (f.lineup_id is not null) as favorite
    from staff_lineups l left join users u on u.id = l.created_by
      left join staff_lineup_favorites f on f.lineup_id = l.id and f.staff_user_id = ${ctx.userId}
    where l.team_id = ${ctx.teamId} and (${id ?? null}::uuid is null or l.id = ${id ?? null}::uuid)
    order by l.updated_at desc, l.id limit 500`;
}

/** Variations as stored by this and the earlier server version (`videoURL`), answered with snake_case keys. */
function apiVariations(stored: unknown): unknown[] {
  if (!Array.isArray(stored)) return [];
  return stored.map((entry) => {
    const variation = (entry ?? {}) as Record<string, unknown>;
    return {
      ...(typeof variation.id === "string" ? { id: variation.id.toLowerCase() } : {}),
      position: variation.position ?? "", technique: variation.technique ?? "standard", aim: variation.aim ?? "",
      video_url: variation.video_url ?? variation.videoURL ?? null,
    };
  });
}

function apiLineup(row: Awaited<ReturnType<typeof teamLineupRows>>[number]) {
  return {
    id: row.id, title: row.title, map: row.map, side: row.side, type: row.type, details: row.details,
    video_url: row.video_url, author: row.author, created_at: row.created_at.toISOString(), visibility: "team",
    is_favorite: row.favorite, variations: apiVariations(row.variations),
  };
}

/** Team-shared site lineups for staff and confirmed team players. */
export async function getTeamLineups(req: Request, deps: Deps): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  const rows = await teamLineupRows(deps.sql, ctx);
  return ok({ lineups: rows.map(apiLineup) });
}

const forbiddenEdit = () => new ApiError("forbidden", "Only the author or staff can edit this lineup", 403);

/** A team member may add their own shared entry; staff may manage every entry for their team. */
export async function putTeamLineup(req: Request, deps: Deps, rawId?: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  const body = await parseBody(req, TeamLineupInput, MAX_LINEUP_BYTES);
  if (rawId !== undefined && (!z.string().uuid().safeParse(rawId).success || body.id !== rawId.toLowerCase())) {
    throw new ApiError("validation_failed", "Invalid lineup id", 422);
  }
  const videoUrl = body.videoURL || null;
  if (videoUrl && !validVideoUrl(videoUrl)) throw new ApiError("validation_failed", "Invalid video URL", 422);
  if (body.variations.some((variation) => variation.videoURL && !validVideoUrl(variation.videoURL))) throw new ApiError("validation_failed", "Invalid variation video URL", 422);
  const variations = body.variations.map((variation) => ({
    id: variation.id ?? crypto.randomUUID(), position: variation.position, technique: variation.technique, aim: variation.aim,
    video_url: variation.videoURL || null,
  }));
  const now = deps.now();
  const { saved, created } = await deps.sql.begin(async (tx) => {
    // The author check and the write share one transaction and one row lock: a lineup cannot change hands in between.
    const lock = () => tx<{ id: string; created_by: string | null }[]>`
      select id, created_by from staff_lineups where id = ${body.id} and team_id = ${ctx.teamId} for update`;
    let [existing] = await lock();
    let isNew = false;
    if (!existing) {
      const inserted = await tx<{ id: string }[]>`
        insert into staff_lineups (id, team_id, created_by, title, map, side, type, details, video_url, variations, created_at, updated_at)
        values (${body.id}, ${ctx.teamId}, ${ctx.userId}, ${body.title}, ${body.map}, ${body.side}, ${body.type}, ${body.details},
          ${videoUrl}, ${tx.json(variations)}, ${now}, ${now})
        on conflict (id) do nothing returning id`;
      isNew = inserted.length > 0;
      if (!isNew) {
        // A concurrent request with the same id won the insert: this one becomes an edit, unless the id is another team's.
        [existing] = await lock();
        if (!existing) throw new ApiError("forbidden", "This lineup id is unavailable", 403);
      }
    }
    if (existing) {
      if (ctx.role !== "staff" && existing.created_by !== ctx.userId) throw forbiddenEdit();
      await tx`
        update staff_lineups set title = ${body.title}, map = ${body.map}, side = ${body.side}, type = ${body.type},
          details = ${body.details}, video_url = ${videoUrl}, variations = ${tx.json(variations)}, updated_at = ${now}
        where id = ${body.id} and team_id = ${ctx.teamId}`;
    }
    if (body.isFavorite) {
      await tx`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${body.id}, ${ctx.userId}) on conflict do nothing`;
    } else {
      await tx`delete from staff_lineup_favorites where lineup_id = ${body.id} and staff_user_id = ${ctx.userId}`;
    }
    const [row] = await teamLineupRows(tx, ctx, body.id);
    return { saved: row, created: isNew };
  });
  return ok({ lineup: apiLineup(saved) }, created ? 201 : 200);
}

export async function deleteTeamLineup(req: Request, deps: Deps, id: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  if (!z.string().uuid().safeParse(id).success) throw new ApiError("not_found", "No such lineup", 404);
  const lineupId = id.toLowerCase();
  await deps.sql.begin(async (tx) => {
    const [row] = await tx<{ created_by: string | null }[]>`
      select created_by from staff_lineups where id = ${lineupId} and team_id = ${ctx.teamId} for update`;
    if (!row) throw new ApiError("not_found", "No such lineup", 404);
    if (ctx.role !== "staff" && row.created_by !== ctx.userId) throw new ApiError("forbidden", "Only the author or staff can delete this lineup", 403);
    await tx`delete from staff_lineups where id = ${lineupId} and team_id = ${ctx.teamId}`;
  });
  return ok({ deleted: true });
}

export async function setTeamLineupFavorite(req: Request, deps: Deps, id: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  if (!z.string().uuid().safeParse(id).success) throw new ApiError("not_found", "No such lineup", 404);
  const lineupId = id.toLowerCase();
  const body = await parseBody(req, TeamLineupFavoriteInput, 1024);
  const [lineup] = await deps.sql<{ id: string }[]>`select id from staff_lineups where id = ${lineupId} and team_id = ${ctx.teamId}`;
  if (!lineup) throw new ApiError("not_found", "No such lineup", 404);
  if (body.isFavorite) await deps.sql`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${lineupId}, ${ctx.userId}) on conflict do nothing`;
  else await deps.sql`delete from staff_lineup_favorites where lineup_id = ${lineupId} and staff_user_id = ${ctx.userId}`;
  return ok({ is_favorite: body.isFavorite, isFavorite: body.isFavorite });
}
