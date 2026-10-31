import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { imageResponse, staffImage } from "@/lib/staff/notes";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ playerId: string; kind: string }> };

/** `GET /staff/files/<playerId>/<kind>`: a staff image of a player of the signed-in staff member's own team. */
export async function GET(_req: Request, { params }: Context): Promise<Response> {
  return imageResponse(async () => {
    // First, before the params or any data: a route handler is reachable by URL, the panel layout does not guard it.
    const ctx = await requireStaffContext("status");
    const { playerId, kind } = await params;
    return staffImage(defaultDeps(), ctx, playerId, kind);
  });
}
