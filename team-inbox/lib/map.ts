import { now, robotName } from "./common";
import { floorsOf, mapImageMeta, rememberFrame, saveMapImage } from "./floors";
import { ApiError } from "./http";
import { cleanMapFrame, cleanMapImage } from "./robot-data";
import { getStore } from "./store";
import { MAP_TRAIL_MAX } from "./types";

// robot, about once a second while moving and every 15 s idle:
// { robot, mapName?, pose: { x, y, theta }, places?, scan?, follower?, target?, remainingMeters?, maps? }
export async function postMap(body: Record<string, unknown>) {
  const robot = robotName(body.robot);
  const cleaned = cleanMapFrame(body, robot, now());
  if ("error" in cleaned) throw new ApiError(400, cleaned.error);
  const map = await getStore().saveMap(cleaned.frame, MAP_TRAIL_MAX);
  await rememberFrame(cleaned.frame);
  return { ok: true, places: map.places.length, scan: map.scan.length, trail: map.trail.length };
}

export async function readMap(robot: string) {
  return getStore().getMap(robotName(robot));
}

// the dashboard's map: the latest frame, the floor plan of its map (when otto sent one) and the floors otto knows.
// a robot nobody heard of: { map: null }
export async function mapView(robotRaw: string) {
  const robot = robotName(robotRaw);
  const [map, places] = await Promise.all([readMap(robot), getStore().listPlaces()]);
  const floors = await floorsOf(robot, places);
  const image = map?.mapName ? await mapImageMeta(robot, map.mapName) : null;
  return { map, ...(image ? { image } : {}), ...(floors.length ? { floors } : {}) };
}

// robot: the floor plan of one map, only sent when it changed. -> 204
export async function postMapImage(body: Record<string, unknown>) {
  const robot = robotName(body.robot);
  const cleaned = cleanMapImage(body);
  if ("error" in cleaned) throw new ApiError(400, cleaned.error);
  return saveMapImage(robot, cleaned.image);
}
