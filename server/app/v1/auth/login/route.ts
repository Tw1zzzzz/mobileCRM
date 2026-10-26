import { loginHandler } from "@/lib/auth/login";
import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";

export const dynamic = "force-dynamic";

export const POST = (req: Request) => handle(() => loginHandler(req, defaultDeps()));
