import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { deleteTeamCalendarEvent, updateTeamCalendarEvent } from "@/lib/calendar-api";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ eventId: string }> };
export const PUT = (req: Request, { params }: Context) =>
  handle(async () => updateTeamCalendarEvent(req, defaultDeps(), (await params).eventId));
export const DELETE = (req: Request, { params }: Context) =>
  handle(async () => deleteTeamCalendarEvent(req, defaultDeps(), (await params).eventId));
