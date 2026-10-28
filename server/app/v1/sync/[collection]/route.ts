import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getSyncDocument, putSyncDocument } from "@/lib/sync";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ collection: string }> };

export const GET = (req: Request, { params }: Context) =>
  handle(async () => getSyncDocument(req, defaultDeps(), (await params).collection));
export const PUT = (req: Request, { params }: Context) =>
  handle(async () => putSyncDocument(req, defaultDeps(), (await params).collection));
