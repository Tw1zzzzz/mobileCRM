import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { listSync } from "@/lib/sync";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => listSync(req, defaultDeps()));
