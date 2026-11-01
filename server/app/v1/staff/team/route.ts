import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getStaffTeam } from "@/lib/staff/api";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => getStaffTeam(req, defaultDeps()));
