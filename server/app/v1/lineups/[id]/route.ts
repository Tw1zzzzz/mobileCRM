import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { deleteTeamLineup, putTeamLineup } from "@/lib/staff/lineups";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ id: string }> };

export const PUT = (req: Request, { params }: Context) => handle(async () => putTeamLineup(req, defaultDeps(), (await params).id));
export const DELETE = (req: Request, { params }: Context) => handle(async () => deleteTeamLineup(req, defaultDeps(), (await params).id));
