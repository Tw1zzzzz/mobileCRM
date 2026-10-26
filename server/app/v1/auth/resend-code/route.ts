import { resendCodeHandler } from "@/lib/auth/verify";
import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";

export const dynamic = "force-dynamic";

export const POST = (req: Request) => handle(() => resendCodeHandler(req, defaultDeps()));
