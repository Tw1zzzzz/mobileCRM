import { logoutHandler } from "@/lib/auth/session";
import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";

export const dynamic = "force-dynamic";

export const POST = (req: Request) => handle(() => logoutHandler(req, defaultDeps()));
