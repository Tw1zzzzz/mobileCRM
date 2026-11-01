import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getStaffPlayerDocuments } from "@/lib/staff/api";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ playerId: string }> };

export const GET = (req: Request, { params }: Context) =>
  handle(async () => getStaffPlayerDocuments(req, defaultDeps(), (await params).playerId));
