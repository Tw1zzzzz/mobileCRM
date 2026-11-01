import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { setTeamLineupFavorite } from "@/lib/staff/lineups";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ id: string }> };

export const PUT = (req: Request, { params }: Context) => handle(async () => setTeamLineupFavorite(req, defaultDeps(), (await params).id));
