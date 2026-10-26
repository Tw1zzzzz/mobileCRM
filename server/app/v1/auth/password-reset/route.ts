import { passwordResetHandler } from "@/lib/auth/reset";
import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";

export const dynamic = "force-dynamic";

export const POST = (req: Request) => handle(() => passwordResetHandler(req, defaultDeps()));
