import type { Deps } from "@/lib/deps";
import type { StaffContext } from "@/lib/staff/access";
import type { CalendarTag } from "@/lib/staff/calendar";
import { STAFF_TIME_ZONE, dayOf } from "@/lib/staff/metrics";

/** One calendar event of today, as the header card of «Обзор» shows it. */
export interface TodayEvent {
  title: string;
  /** «12:00» in the staff time zone. */
  time: string;
  tag: CalendarTag;
  playerName: string | null;
}

const HOUR_MS = 3_600_000;
/** An event starting «today» in any zone lies within this distance of now; the day check below is exact. */
const SEARCH_RADIUS_MS = 40 * HOUR_MS;
const MAX_EVENTS = 50;

function timeOf(instant: Date, tz: string): string {
  return new Intl.DateTimeFormat("ru-RU", { timeZone: tz, hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(instant);
}

/**
 * The events of the team's calendar that start today in the staff zone, earliest first. Personal events are the
 * signed-in staff member's own; team and player events are shared, as on the calendar page.
 */
export async function loadTodayEvents(deps: Deps, ctx: StaffContext, tz: string = STAFF_TIME_ZONE): Promise<TodayEvent[]> {
  const now = deps.now();
  const today = dayOf(now, tz);
  const rows = await deps.sql<{ title: string; tag: CalendarTag; starts_at: Date; player_name: string | null }[]>`
    select e.title, e.tag, e.starts_at, u.nickname as player_name
    from staff_calendar_events e left join users u on u.id = e.player_id
    where e.team_id = ${ctx.teamId}
      and e.starts_at >= ${new Date(now.getTime() - SEARCH_RADIUS_MS)} and e.starts_at <= ${new Date(now.getTime() + SEARCH_RADIUS_MS)}
      and (e.tag <> 'personal' or e.created_by = ${ctx.userId})
    order by e.starts_at asc, e.id
    limit ${MAX_EVENTS}`;
  return rows
    .filter((row) => dayOf(row.starts_at, tz) === today)
    .map((row) => ({ title: row.title, time: timeOf(row.starts_at, tz), tag: row.tag, playerName: row.player_name }));
}
