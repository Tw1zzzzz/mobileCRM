import { defaultDeps } from "@/lib/deps";
import { requireStaffContext } from "@/lib/staff/current";
import { photoResponse, staffMealPhoto } from "@/lib/staff/photos";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ playerId: string; mealId: string }> };

/** `GET /staff/photos/<playerId>/<mealId>`: the JPEG of a meal of the signed-in staff member's own team player. */
export async function GET(_req: Request, { params }: Context): Promise<Response> {
  return photoResponse(async () => {
    // First, before the params or any data: a route handler is reachable by URL, the panel layout does not guard it.
    const ctx = await requireStaffContext("status");
    const { playerId, mealId } = await params;
    return staffMealPhoto(defaultDeps(), ctx, playerId, mealId);
  });
}
