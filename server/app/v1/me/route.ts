import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { deleteMe, getMe, patchMe } from "@/lib/me";

export const dynamic = "force-dynamic";

export const GET = (req: Request) => handle(() => getMe(req, defaultDeps()));
export const PATCH = (req: Request) => handle(() => patchMe(req, defaultDeps()));
export const DELETE = (req: Request) => handle(() => deleteMe(req, defaultDeps()));
