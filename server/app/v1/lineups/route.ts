import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getTeamLineups, putTeamLineup } from "@/lib/staff/lineups";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => getTeamLineups(req, defaultDeps()));
export const POST = (req: Request) => handle(() => putTeamLineup(req, defaultDeps()));
