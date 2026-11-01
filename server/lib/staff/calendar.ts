import { z } from "zod";
import type { Deps } from "@/lib/deps";
import { rosterMembers, type StaffContext } from "@/lib/staff/access";
import { STAFF_TIME_ZONE } from "@/lib/staff/metrics";

export const CALENDAR_TAGS = ["personal", "team", "player"] as const;
export type CalendarTag = (typeof CALENDAR_TAGS)[number];
export type CalendarFilter = "all" | CalendarTag | `player:${string}`;

export interface CalendarPlayer { id: string; nickname: string }
export interface CalendarEventRow {
  id: string; title: string; details: string; tag: CalendarTag;
  playerId: string | null; playerName: string | null;
  startsAt: Date; endsAt: Date; links: string[]; createdBy: string | null;
}
export interface CalendarPageData {
  year: number; month: number; selectedDay: string; filter: CalendarFilter;
  players: CalendarPlayer[]; events: CalendarEventRow[];
  error: CalendarError | null; openCreate: boolean;
}

export type CalendarError = "invalid" | "save-failed" | "not-found";
export const CALENDAR_ERRORS: readonly CalendarError[] = ["invalid", "save-failed", "not-found"];
export const CALENDAR_ERROR_TEXT: Record<CalendarError, string> = {
  invalid: "Проверьте поля события: даты, название, ссылку и выбранного игрока.",
  "save-failed": "Не удалось сохранить событие. Обновите страницу и попробуйте ещё раз.",
  "not-found": "Событие больше недоступно.",
};

const EventFields = z.object({
  id: z.string().uuid().optional().or(z.literal("")),
  title: z.string().trim().min(1).max(120),
  details: z.string().trim().max(4000),
  tag: z.enum(CALENDAR_TAGS),
  playerId: z.string().uuid().optional().or(z.literal("")),
  startsAt: z.string().min(1),
  endsAt: z.string().min(1),
  links: z.string().max(4000),
});

function first(value: string | string[] | undefined): string | undefined { return Array.isArray(value) ? value[0] : value; }
function dateKey(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: STAFF_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(date);
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}
function monthParts(raw: string | undefined): { year: number; month: number } {
  if (raw && /^(\d{4})-(\d{2})$/.test(raw)) {
    const [year, month] = raw.split("-").map(Number);
    if (year >= 2000 && year <= 2100 && month >= 1 && month <= 12) return { year, month };
  }
  const [year, month] = dateKey(new Date()).split("-").map(Number);
  return { year, month };
}
function parseDay(raw: string | undefined, year: number, month: number): string {
  if (raw && /^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const date = new Date(`${raw}T00:00:00Z`);
    if (!Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === raw) return raw;
  }
  const today = dateKey(new Date());
  const [todayYear, todayMonth] = today.split("-").map(Number);
  return todayYear === year && todayMonth === month ? today : `${year}-${String(month).padStart(2, "0")}-01`;
}
function monthWindow(year: number, month: number): { gridStart: Date; gridEnd: Date } {
  const firstDay = new Date(Date.UTC(year, month - 1, 1));
  const lastDay = new Date(Date.UTC(year, month, 0));
  const offset = (firstDay.getUTCDay() + 6) % 7;
  const trailing = 6 - ((lastDay.getUTCDay() + 6) % 7);
  return {
    gridStart: new Date(Date.UTC(year, month - 1, 1 - offset) - 86_400_000),
    gridEnd: new Date(Date.UTC(year, month, trailing + 1) + 86_400_000),
  };
}
function localDateTime(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  const target = Date.UTC(year, month - 1, day, hour, minute);
  const base = new Date(target);
  if (base.getUTCFullYear() !== year || base.getUTCMonth() + 1 !== month || base.getUTCDate() !== day || base.getUTCHours() !== hour || base.getUTCMinutes() !== minute) return null;
  const format = new Intl.DateTimeFormat("en-CA", { timeZone: STAFF_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  let utc = target;
  for (let i = 0; i < 2; i++) {
    const parts = Object.fromEntries(format.formatToParts(new Date(utc)).map((part) => [part.type, part.value]));
    utc += target - Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute);
  }
  const result = new Date(utc);
  const actual = Object.fromEntries(format.formatToParts(result).map((part) => [part.type, part.value]));
  if (+actual.year !== year || +actual.month !== month || +actual.day !== day || +actual.hour !== hour || +actual.minute !== minute) return null;
  return result;
}
export function calendarDayKey(date: Date): string { return dateKey(date); }
export function calendarInputForDay(day: string, hour: number): string {
  const value = `${day}T${String(hour).padStart(2, "0")}:00`;
  const date = localDateTime(value);
  return date ? calendarDateTimeInput(date) : value;
}
function validLink(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && !!url.hostname && !url.username && !url.password;
  } catch { return false; }
}
function field(form: FormData, name: string, fallback = ""): string {
  const value = form.get(name);
  return typeof value === "string" ? value : fallback;
}

export function calendarDateTimeInput(date: Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: STAFF_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(date);
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}T${value.hour}:${value.minute}`;
}

export async function loadCalendarPage(
  deps: Deps, ctx: StaffContext,
  params: { month?: string | string[]; day?: string | string[]; tag?: string | string[]; error?: string | string[]; new?: string | string[] },
): Promise<CalendarPageData> {
  const period = monthParts(first(params.month));
  const players = await rosterMembers(deps, ctx);
  const range = monthWindow(period.year, period.month);
  const rows = await deps.sql<{
    id: string; title: string; details: string; tag: CalendarTag; player_id: string | null; player_name: string | null;
    starts_at: Date; ends_at: Date; links: string[]; created_by: string | null;
  }[]>`
    select e.id, e.title, e.details, e.tag, e.player_id, u.nickname as player_name, e.starts_at, e.ends_at, e.links, e.created_by
    from staff_calendar_events e left join users u on u.id = e.player_id
    where e.team_id = ${ctx.teamId} and e.starts_at < ${range.gridEnd} and e.ends_at >= ${range.gridStart}
      and (e.tag <> 'personal' or e.created_by = ${ctx.userId})
    order by e.starts_at asc limit 500`;
  const rawTag = first(params.tag);
  const filter: CalendarFilter = rawTag === "personal" || rawTag === "team" || rawTag === "player" ? rawTag
    : rawTag?.startsWith("player:") && players.some((p) => p.id === rawTag.slice(7)) ? `player:${rawTag.slice(7)}` : "all";
  const rawError = first(params.error);
  return {
    ...period,
    selectedDay: parseDay(first(params.day), period.year, period.month),
    filter,
    players,
    events: rows.map((row) => ({
      id: row.id, title: row.title, details: row.details, tag: row.tag, playerId: row.player_id,
      playerName: row.player_name, startsAt: row.starts_at, endsAt: row.ends_at, links: row.links ?? [], createdBy: row.created_by,
    })),
    error: CALENDAR_ERRORS.find((error) => error === rawError) ?? null,
    openCreate: first(params.new) === "1",
  };
}

export async function saveCalendarEvent(deps: Deps, ctx: StaffContext, form: FormData): Promise<{ ok: true; month: string; day: string } | { ok: false; error: CalendarError }> {
  const parsed = EventFields.safeParse({
    id: field(form, "id"), title: field(form, "title"), details: field(form, "details"),
    tag: field(form, "tag", "team"), playerId: field(form, "playerId"),
    startsAt: field(form, "startsAt"), endsAt: field(form, "endsAt"), links: field(form, "links"),
  });
  if (!parsed.success) return { ok: false, error: "invalid" };
  const input = parsed.data;
  const start = localDateTime(input.startsAt);
  const end = localDateTime(input.endsAt);
  const links = input.links.split(/[\s,]+/).filter(Boolean);
  if (!start || !end || end <= start || end.getTime() - start.getTime() > 7 * 86_400_000 ||
      links.length > 20 || links.some((link) => !validLink(link)) ||
      (input.tag === "player" && (!input.playerId || !(await rosterMembers(deps, ctx)).some((player) => player.id === input.playerId)))) {
    return { ok: false, error: "invalid" };
  }
  const id = input.id || null;
  const now = deps.now();
  if (id) {
    const rows = await deps.sql<{ id: string }[]>`
      update staff_calendar_events set title = ${input.title}, details = ${input.details}, tag = ${input.tag},
        player_id = ${input.tag === "player" ? input.playerId || null : null}, starts_at = ${start}, ends_at = ${end},
        links = ${deps.sql.array(links)}, updated_at = ${now}
      where id = ${id} and team_id = ${ctx.teamId} and (tag <> 'personal' or created_by = ${ctx.userId}) returning id`;
    if (!rows.length) return { ok: false, error: "not-found" };
  } else {
    await deps.sql`
      insert into staff_calendar_events (team_id, created_by, title, details, tag, player_id, starts_at, ends_at, links, created_at, updated_at)
      values (${ctx.teamId}, ${ctx.userId}, ${input.title}, ${input.details}, ${input.tag},
        ${input.tag === "player" ? input.playerId || null : null}, ${start}, ${end}, ${deps.sql.array(links)}, ${now}, ${now})`;
  }
  const day = dateKey(start);
  return { ok: true, month: day.slice(0, 7), day };
}

export async function deleteCalendarEvent(deps: Deps, ctx: StaffContext, id: string): Promise<boolean> {
  const rows = await deps.sql<{ id: string }[]>`
    delete from staff_calendar_events where id = ${id} and team_id = ${ctx.teamId}
      and (tag <> 'personal' or created_by = ${ctx.userId}) returning id`;
  return rows.length > 0;
}

export function eventTagLabel(event: Pick<CalendarEventRow, "tag" | "playerName">): string {
  if (event.tag === "personal") return "Личное";
  if (event.tag === "team") return "Команда";
  return event.playerName ? `Игрок · ${event.playerName}` : "Игрок команды";
}
