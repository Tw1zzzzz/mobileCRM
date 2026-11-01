import { defaultDeps } from "@/lib/deps";
import { handle } from "@/lib/http";
import { getStaffMealPhoto } from "@/lib/staff/api";

export const dynamic = "force-dynamic";

type Context = { params: Promise<{ playerId: string; mealId: string }> };

export const GET = (req: Request, { params }: Context) =>
  handle(async () => {
    const { playerId, mealId } = await params;
    return getStaffMealPhoto(req, defaultDeps(), playerId, mealId);
  });
