import { z } from "zod";
import type { Deps } from "@/lib/deps";
import { requireUser } from "@/lib/auth/tokens";
import { ApiError, ok, parseBody } from "@/lib/http";
import { rateLimit } from "@/lib/rateLimit";

const TEAM_PLAYER_ROLE = "teamPlayer";
const STAFF_ROLE = "staff";

const Uuid = z.string().uuid();
const Body = z.object({
  id: Uuid,
  title: z.string().trim().min(1).max(120),
  details: z.string().trim().max(4000).default(""),
  start: z.string().datetime({ offset: true }),
  end: z.string().datetime({ offset: true }),
  links: z.array(z.string().max(2048).url().refine((value) => {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && !url.username && !url.password;
  })).max(20),
  tag: z.enum(["team", "player"]),
  player_nickname: z.string().trim().min(1).max(40).optional(),
});

interface CalendarMember { id: string; team_id: string | null; role: string; nickname: string; email_verified_at: Date | null }

async function authorize(req: Request, deps: Deps): Promise<CalendarMember> {
  const userId = await requireUser(deps, req);
  await rateLimit(deps, `calendar:${userId}`, 120, 60_000);
  const [member] = await deps.sql<CalendarMember[]>`
    select id, team_id, role, nickname, email_verified_at from users where id = ${userId}`;
  if (!member || !member.team_id || member.email_verified_at === null || (member.role !== STAFF_ROLE && member.role !== TEAM_PLAYER_ROLE)) {
    throw new ApiError("forbidden", "Team calendar access required", 403);
  }
  return member;
}

async function requireStaff(member: CalendarMember): Promise<void> {
  if (member.role !== STAFF_ROLE) throw new ApiError("forbidden", "Staff calendar access required", 403);
}

function responseEvent(row: {
  id: string; title: string; details: string; starts_at: Date; ends_at: Date; links: string[];
  tag: "team" | "player"; nickname: string | null; created_by: string | null;
}) {
  return {
    id: row.id, title: row.title, details: row.details,
    start: row.starts_at.toISOString(), end: row.ends_at.toISOString(),
    links: row.links ?? [], tag: row.tag, player_nickname: row.nickname,
    owner_id: row.created_by,
  };
}

export async function getTeamCalendar(req: Request, deps: Deps): Promise<Response> {
  const member = await authorize(req, deps);
  const rows = await deps.sql<{
    id: string; title: string; details: string; starts_at: Date; ends_at: Date; links: string[];
    tag: "team" | "player"; nickname: string | null; created_by: string | null;
  }[]>`
    select e.id, e.title, e.details, e.starts_at, e.ends_at, e.links, e.tag,
      u.nickname, e.created_by
    from staff_calendar_events e
    left join users u on u.id = e.player_id
    where e.team_id = ${member.team_id}
      and e.tag in ('team', 'player')
      and (${member.role === STAFF_ROLE} or e.tag = 'team' or e.player_id = ${member.id})
    order by e.starts_at asc limit 500`;
  const players = member.role === STAFF_ROLE
    ? await deps.sql<{ id: string; nickname: string }[]>`
        select id, nickname from users where team_id = ${member.team_id} and role = ${TEAM_PLAYER_ROLE}
          and email_verified_at is not null order by created_at asc`
    : [];
  return ok({ team_id: member.team_id, events: rows.map(responseEvent), players });
}

async function writeCalendarEvent(req: Request, deps: Deps, member: CalendarMember, eventId?: string): Promise<Response> {
  await requireStaff(member);
  const body = await parseBody(req, Body, 32_000);
  const start = new Date(body.start);
  const end = new Date(body.end);
  if (end <= start || end.getTime() - start.getTime() > 7 * 24 * 60 * 60 * 1000) {
    throw new ApiError("validation_failed", "Invalid event time range", 422);
  }
  let playerId: string | null = null;
  if (body.tag === "player") {
    if (!body.player_nickname) throw new ApiError("validation_failed", "Player is required", 422);
    const [player] = await deps.sql<{ id: string }[]>`
      select id from users where team_id = ${member.team_id} and role = ${TEAM_PLAYER_ROLE}
        and email_verified_at is not null and nickname = ${body.player_nickname}`;
    if (!player) throw new ApiError("validation_failed", "Player is not in this team", 422);
    playerId = player.id;
  }
  const now = deps.now();
  if (eventId) {
    if (eventId !== body.id) throw new ApiError("validation_failed", "Event id does not match", 422);
    const rows = await deps.sql<{ id: string }[]>`
      update staff_calendar_events set title = ${body.title}, details = ${body.details}, tag = ${body.tag},
        player_id = ${playerId}, starts_at = ${start}, ends_at = ${end}, links = ${deps.sql.array(body.links)}, updated_at = ${now}
      where id = ${eventId} and team_id = ${member.team_id} and tag in ('team', 'player') returning id`;
    if (!rows.length) throw new ApiError("not_found", "Calendar event not found", 404);
  } else {
    const rows = await deps.sql<{ id: string }[]>`
      insert into staff_calendar_events (id, team_id, created_by, title, details, tag, player_id, starts_at, ends_at, links, created_at, updated_at)
      values (${body.id}, ${member.team_id}, ${member.id}, ${body.title}, ${body.details}, ${body.tag}, ${playerId},
        ${start}, ${end}, ${deps.sql.array(body.links)}, ${now}, ${now})
      on conflict (id) do update set title = excluded.title, details = excluded.details, tag = excluded.tag,
        player_id = excluded.player_id, starts_at = excluded.starts_at, ends_at = excluded.ends_at,
        links = excluded.links, updated_at = excluded.updated_at
      where staff_calendar_events.team_id = excluded.team_id and staff_calendar_events.tag in ('team', 'player')
      returning id`;
    if (!rows.length) throw new ApiError("not_found", "Calendar event not found", 404);
  }
  return ok({ id: body.id });
}

export async function createTeamCalendarEvent(req: Request, deps: Deps): Promise<Response> {
  const member = await authorize(req, deps);
  return writeCalendarEvent(req, deps, member);
}

export async function updateTeamCalendarEvent(req: Request, deps: Deps, eventId: string): Promise<Response> {
  const member = await authorize(req, deps);
  if (!Uuid.safeParse(eventId).success) throw new ApiError("validation_failed", "Invalid event id", 422);
  return writeCalendarEvent(req, deps, member, eventId);
}

export async function deleteTeamCalendarEvent(req: Request, deps: Deps, eventId: string): Promise<Response> {
  const member = await authorize(req, deps);
  await requireStaff(member);
  if (!Uuid.safeParse(eventId).success) throw new ApiError("validation_failed", "Invalid event id", 422);
  const rows = await deps.sql<{ id: string }[]>`
    delete from staff_calendar_events where id = ${eventId} and team_id = ${member.team_id}
      and tag in ('team', 'player') returning id`;
  if (!rows.length) throw new ApiError("not_found", "Calendar event not found", 404);
  return ok({ id: eventId });
}
