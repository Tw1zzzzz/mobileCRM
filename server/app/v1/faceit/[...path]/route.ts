import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { proxyFaceit } from "@/lib/faceit/proxy";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ path: string[] }> };
export const GET = (req: Request, { params }: Context) =>
  handle(async () => proxyFaceit(req, defaultDeps(), (await params).path));
