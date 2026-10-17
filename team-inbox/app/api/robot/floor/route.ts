import { robotName } from "@/lib/common";
import { mapImageMeta, rememberedSpots } from "@/lib/floors";
import { ApiError, jsonEtag, requireStaff, route, text } from "@/lib/http";
import { REAL_ROBOT, isSimRobot } from "@/lib/robots";

export const dynamic = "force-dynamic";

// dashboard: one floor otto is not on right now: { name, spots (where otto reported them), image? }
export const GET = route(async (req) => {
  await requireStaff(req);
  const url = new URL(req.url);
  const robot = robotName(url.searchParams.get("robot"));
  const name = text(url.searchParams.get("map"), 80);
  if (!name) throw new ApiError(400, "map is required");
  const [spots, image] = await Promise.all([rememberedSpots(isSimRobot(robot) ? REAL_ROBOT : robot, name), mapImageMeta(robot, name)]);
  return jsonEtag(req, { name, spots, ...(image ? { image } : {}) });
});
