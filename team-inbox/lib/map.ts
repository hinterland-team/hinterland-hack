import { now, robotName } from "./common";
import { ApiError } from "./http";
import { cleanMapFrame } from "./robot-data";
import { getStore } from "./store";
import { MAP_TRAIL_MAX } from "./types";

// robot, about once a second while moving and every 15 s idle:
// { robot, mapName?, pose: { x, y, theta }, places?, scan?, follower?, target?, remainingMeters? }
export async function postMap(body: Record<string, unknown>) {
  const robot = robotName(body.robot);
  const cleaned = cleanMapFrame(body, robot, now());
  if ("error" in cleaned) throw new ApiError(400, cleaned.error);
  const map = await getStore().saveMap(cleaned.frame, MAP_TRAIL_MAX);
  return { ok: true, places: map.places.length, scan: map.scan.length, trail: map.trail.length };
}

export async function readMap(robot: string) {
  return getStore().getMap(robotName(robot));
}
