import { robotName } from "@/lib/common";
import { caller, json, readJson, requireRobot, route } from "@/lib/http";
import { postMap, readMap } from "@/lib/map";

export const dynamic = "force-dynamic";

// robot: { robot, mapName?, pose, places?, scan?, follower?, target?, remainingMeters? }. keeps the latest + a trail
export const POST = route(async (req) => {
  requireRobot(req);
  return json(await postMap(await readJson(req)));
});

// dashboard: { map: latest frame with trail, or null }
export const GET = route(async (req) => {
  await caller(req);
  return json({ map: await readMap(robotName(new URL(req.url).searchParams.get("robot"))) });
});
