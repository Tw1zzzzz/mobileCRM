import { handle, ok } from "@/lib/http";
import type { Deps } from "@/lib/deps";

export function health(_req: Request, deps: Deps): Promise<Response> {
  return handle(async () => {
    await deps.sql`select 1`;
    return ok({ ok: true, db: true });
  });
}
