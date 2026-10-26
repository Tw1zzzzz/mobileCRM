import { defaultDeps } from "@/lib/deps";
import { health } from "@/lib/health";
import { handle } from "@/lib/http";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => health(req, defaultDeps()));
