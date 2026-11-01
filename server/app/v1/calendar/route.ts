import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { createTeamCalendarEvent, getTeamCalendar } from "@/lib/calendar-api";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => getTeamCalendar(req, defaultDeps()));
export const POST = (req: Request) => handle(() => createTeamCalendarEvent(req, defaultDeps()));
