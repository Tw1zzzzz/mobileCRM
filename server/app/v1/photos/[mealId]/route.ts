import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getPhoto, putPhoto } from "@/lib/photos";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ mealId: string }> };

export const GET = (req: Request, { params }: Context) =>
  handle(async () => getPhoto(req, defaultDeps(), (await params).mealId));
export const PUT = (req: Request, { params }: Context) =>
  handle(async () => putPhoto(req, defaultDeps(), (await params).mealId));
