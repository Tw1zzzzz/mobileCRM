import { z } from "zod";
import { requireUser } from "@/lib/auth/tokens";
import type { Deps } from "@/lib/deps";
import { ApiError, ok, parseBody } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";
import type { StaffContext } from "@/lib/staff/access";

export const LINEUP_MAPS = ["mirage", "inferno", "nuke", "ancient", "anubis", "dust2", "train"] as const;
export const LINEUP_TYPES = ["smoke", "flash", "molotov", "grenade", "script"] as const;
export const LINEUP_SIDES = ["t", "ct"] as const;
export type LineupMap = (typeof LINEUP_MAPS)[number];
export type StaffLineupType = (typeof LINEUP_TYPES)[number];
export type LineupSide = (typeof LINEUP_SIDES)[number];

const API_LINEUP_TYPES = LINEUP_TYPES;
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
export interface StaffLineupsPage {
  filters: LineupFilter;
  entries: StaffLineup[];
  selected: StaffLineup | null;
  totalForTeam: number;
  hasMore: boolean;
  error: LineupError | null;
  notice: "created" | "saved" | "deleted" | null;
}

const ERRORS: readonly LineupError[] = ["invalid", "not-found", "save-failed"];
const NOTICES: readonly NonNullable<StaffLineupsPage["notice"]>[] = ["created", "saved", "deleted"];
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
  params: { q?: string | string[]; map?: string | string[]; type?: string | string[]; side?: string | string[]; fav?: string | string[]; entry?: string | string[]; error?: string | string[]; notice?: string | string[] },
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
  const [{ count }] = await deps.sql<{ count: number }[]>`
    select count(*)::int as count from staff_lineups where team_id = ${ctx.teamId}`;
  const rows = await deps.sql<{
    id: string; title: string; map: LineupMap; side: LineupSide; type: StaffLineupType; details: string;
    video_url: string | null; author: string | null; created_at: Date; favorite: boolean;
  }[]>`
    select l.id, l.title, l.map, l.side, l.type, l.details, l.video_url,
      coalesce(u.nickname, 'Бывший участник') as author, l.created_at,
      (f.lineup_id is not null) as favorite
    from staff_lineups l
    left join users u on u.id = l.created_by
    left join staff_lineup_favorites f on f.lineup_id = l.id and f.staff_user_id = ${ctx.userId}
    where l.team_id = ${ctx.teamId}
      and (${filters.map} = 'all' or l.map = ${filters.map})
      and (${filters.type} = 'all' or l.type = ${filters.type})
      and (${filters.side} = 'all' or l.side = ${filters.side})
      and (${filters.favorites} = false or f.lineup_id is not null)
      and (${filters.q} = '' or strpos(lower(l.title || ' ' || l.map || ' ' || l.details), lower(${filters.q})) > 0)
    order by l.updated_at desc, l.id
    limit 501`;
  const entries = rows.slice(0, 500).map((row) => ({
    id: row.id, title: row.title, map: row.map, side: row.side, type: row.type, details: row.details,
    videoUrl: row.video_url, author: row.author ?? "Бывший участник", createdAt: row.created_at, favorite: row.favorite,
  }));
  const selected = (filters.entry ? entries.find((entry) => entry.id === filters.entry) : null) ?? entries[0] ?? null;
  const rawError = first(params.error);
  const rawNotice = first(params.notice);
  return {
    filters, entries, selected, totalForTeam: count, hasMore: rows.length > 500,
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
  const parsed = FormFields.safeParse({
    id: field(form, "id"), title: field(form, "title"), map: field(form, "map"), side: field(form, "side"),
    type: field(form, "type"), details: field(form, "details"), videoUrl: field(form, "videoUrl"),
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

const VariationInput = z.object({
  id: z.string().uuid().optional(),
  position: z.string().trim().max(240),
  technique: z.enum(["standard", "jump", "run", "runJump", "walk", "rightClick"]),
  aim: z.string().trim().max(400),
  videoURL: z.string().trim().max(2048).nullable().optional(),
});
const TeamLineupInput = z.object({
  id: z.string().uuid(),
  title: z.string().trim().min(1).max(120),
  map: z.enum(LINEUP_MAPS),
  side: z.enum(LINEUP_SIDES),
  type: z.enum(API_LINEUP_TYPES),
  details: z.string().trim().max(4000),
  videoURL: z.string().trim().max(2048).nullable().optional(),
  visibility: z.literal("team"),
  isFavorite: z.boolean().default(false),
  variations: z.array(VariationInput).max(20).default([]),
});
const TeamLineupFavoriteInput = z.object({ isFavorite: z.boolean() });

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

async function teamLineupRows(deps: Deps, ctx: TeamMemberContext, id?: string) {
  return deps.sql<{
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

function apiLineup(row: Awaited<ReturnType<typeof teamLineupRows>>[number]) {
  return {
    id: row.id, title: row.title, map: row.map, side: row.side, type: row.type, details: row.details,
    videoURL: row.video_url, author: row.author, createdAt: row.created_at.toISOString(), visibility: "team",
    isFavorite: row.favorite, variations: Array.isArray(row.variations) ? row.variations : [],
  };
}

/** Team-shared site lineups for staff and confirmed team players. */
export async function getTeamLineups(req: Request, deps: Deps): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  const rows = await teamLineupRows(deps, ctx);
  return ok({ lineups: rows.map(apiLineup) });
}

/** A team member may add their own shared entry; staff may manage every entry for their team. */
export async function putTeamLineup(req: Request, deps: Deps, rawId?: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  const body = await parseBody(req, TeamLineupInput, MAX_LINEUP_BYTES);
  if ((rawId !== undefined && (!z.string().uuid().safeParse(rawId).success || body.id !== rawId)) || !z.string().uuid().safeParse(body.id).success) {
    throw new ApiError("validation_failed", "Invalid lineup id", 422);
  }
  if (body.videoURL && !validVideoUrl(body.videoURL)) throw new ApiError("validation_failed", "Invalid video URL", 422);
  if (body.variations.some((variation) => variation.videoURL && !validVideoUrl(variation.videoURL))) throw new ApiError("validation_failed", "Invalid variation video URL", 422);
  const [existing] = await deps.sql<{ id: string; created_by: string | null }[]>`
    select id, created_by from staff_lineups where id = ${body.id} and team_id = ${ctx.teamId}`;
  if (existing && ctx.role !== "staff" && existing.created_by !== ctx.userId) throw new ApiError("forbidden", "Only the author or staff can edit this lineup", 403);
  const now = deps.now();
  if (existing) {
    await deps.sql`
      update staff_lineups set title = ${body.title}, map = ${body.map}, side = ${body.side}, type = ${body.type},
        details = ${body.details}, video_url = ${body.videoURL ?? null}, variations = ${deps.sql.json(body.variations)}, updated_at = ${now}
      where id = ${body.id} and team_id = ${ctx.teamId}`;
  } else {
    const inserted = await deps.sql<{ id: string }[]>`
      insert into staff_lineups (id, team_id, created_by, title, map, side, type, details, video_url, variations, created_at, updated_at)
      values (${body.id}, ${ctx.teamId}, ${ctx.userId}, ${body.title}, ${body.map}, ${body.side}, ${body.type}, ${body.details},
        ${body.videoURL ?? null}, ${deps.sql.json(body.variations)}, ${now}, ${now})
      on conflict (id) do nothing returning id`;
    if (!inserted.length) throw new ApiError("forbidden", "This lineup id is unavailable", 403);
  }
  if (body.isFavorite) {
    await deps.sql`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${body.id}, ${ctx.userId}) on conflict do nothing`;
  } else {
    await deps.sql`delete from staff_lineup_favorites where lineup_id = ${body.id} and staff_user_id = ${ctx.userId}`;
  }
  const [saved] = await teamLineupRows(deps, ctx, body.id);
  return ok({ lineup: apiLineup(saved) }, existing ? 200 : 201);
}

export async function deleteTeamLineup(req: Request, deps: Deps, id: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  if (!z.string().uuid().safeParse(id).success) throw new ApiError("not_found", "No such lineup", 404);
  const rows = await deps.sql<{ created_by: string | null }[]>`
    select created_by from staff_lineups where id = ${id} and team_id = ${ctx.teamId}`;
  if (!rows.length) throw new ApiError("not_found", "No such lineup", 404);
  if (ctx.role !== "staff" && rows[0].created_by !== ctx.userId) throw new ApiError("forbidden", "Only the author or staff can delete this lineup", 403);
  await deps.sql`delete from staff_lineups where id = ${id} and team_id = ${ctx.teamId}`;
  return ok({ deleted: true });
}

export async function setTeamLineupFavorite(req: Request, deps: Deps, id: string): Promise<Response> {
  const ctx = await teamMemberContext(req, deps);
  if (!z.string().uuid().safeParse(id).success) throw new ApiError("not_found", "No such lineup", 404);
  const body = await parseBody(req, TeamLineupFavoriteInput, 1024);
  const [lineup] = await deps.sql<{ id: string }[]>`select id from staff_lineups where id = ${id} and team_id = ${ctx.teamId}`;
  if (!lineup) throw new ApiError("not_found", "No such lineup", 404);
  if (body.isFavorite) await deps.sql`insert into staff_lineup_favorites (lineup_id, staff_user_id) values (${id}, ${ctx.userId}) on conflict do nothing`;
  else await deps.sql`delete from staff_lineup_favorites where lineup_id = ${id} and staff_user_id = ${ctx.userId}`;
  return ok({ isFavorite: body.isFavorite });
}
