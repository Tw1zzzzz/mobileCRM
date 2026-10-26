import { defaultDeps } from "@/lib/deps";
import { health } from "@/lib/health";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => health(req, defaultDeps());
