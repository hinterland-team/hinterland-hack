// floors (robot maps) and the real floor plan images (docs/robot-setup.md sections 1 and 2).
// what otto reported is remembered in the settings store, so it survives a restart like the rest:
// map:list:<robot> = its maps, map:spots:<robot>|<map> = the spots it sent for that map,
// mapimg:meta:<robot>|<map> + mapimg:png:<robot>|<map> = the latest floor plan of that map
import { ApiError } from "./http";
import type { MapFrame, MapImageUpload } from "./robot-data";
import { REAL_ROBOT, isSimRobot } from "./robots";
import { getStore } from "./store";
import type { FloorInfo, MapImageMeta, MapInfo, MapSpot, Place } from "./types";
import { normalizeName } from "./types";

const listKey = (robot: string) => `map:list:${robot}`;
const spotsKey = (robot: string, map: string) => `map:spots:${robot}|${map}`;
const metaKey = (robot: string, map: string) => `mapimg:meta:${robot}|${map}`;
const pngKey = (robot: string, map: string) => `mapimg:png:${robot}|${map}`;

function parse<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

// the maps otto reported (map frame or heartbeat), written only when the list changed
export async function rememberMaps(robot: string, maps: MapInfo[]) {
  const store = getStore();
  const value = JSON.stringify(maps);
  if ((await store.getSetting(listKey(robot))) !== value) await store.setSetting(listKey(robot), value);
}

// after every map frame: the maps list and the spots of the current map, written only when they changed
export async function rememberFrame(frame: MapFrame) {
  const store = getStore();
  if (frame.maps) await rememberMaps(frame.robot, frame.maps);
  if (frame.places && frame.mapName) {
    const value = JSON.stringify(frame.places);
    if ((await store.getSetting(spotsKey(frame.robot, frame.mapName))) !== value) {
      await store.setSetting(spotsKey(frame.robot, frame.mapName), value);
    }
  }
}

export async function rememberedSpots(robot: string, map: string): Promise<MapSpot[]> {
  return parse<MapSpot[]>(await getStore().getSetting(spotsKey(robot, map))) ?? [];
}

// the map the robot is on: its heartbeat (robot-setup.md), else its last map frame
export async function currentMapOf(robot: string): Promise<string | null> {
  const store = getStore();
  const { heartbeat } = await store.getRobot(robot);
  if (heartbeat?.mapName) return heartbeat.mapName;
  return (await store.getMap(robot))?.mapName ?? null;
}

// ---------- floor plan images

export async function saveMapImage(robot: string, img: MapImageUpload): Promise<MapImageMeta> {
  const store = getStore();
  const meta: MapImageMeta = {
    robot,
    mapName: img.mapName,
    width: img.width,
    height: img.height,
    resolution: img.resolution,
    originX: img.originX,
    originY: img.originY,
    hash: img.hash,
    bytes: img.bytes,
    updatedAt: new Date().toISOString(),
  };
  const before = parse<MapImageMeta>(await store.getSetting(metaKey(robot, img.mapName)));
  // the same picture again: only the numbers may have changed
  if (!before || before.hash !== img.hash) await store.setSetting(pngKey(robot, img.mapName), img.png);
  await store.setSetting(metaKey(robot, img.mapName), JSON.stringify(meta));
  return meta;
}

// virtual otto walks on the real floors: it uses the real robot's floor plan of the same map
export async function mapImageMeta(robot: string, map: string): Promise<MapImageMeta | null> {
  const own = parse<MapImageMeta>(await getStore().getSetting(metaKey(robot, map)));
  if (own || !isSimRobot(robot)) return own;
  return parse<MapImageMeta>(await getStore().getSetting(metaKey(REAL_ROBOT, map)));
}

export async function mapImagePng(robot: string, map: string): Promise<{ meta: MapImageMeta; png: Buffer } | null> {
  const meta = await mapImageMeta(robot, map);
  if (!meta) return null;
  const b64 = await getStore().getSetting(pngKey(meta.robot, map));
  return b64 ? { meta, png: Buffer.from(b64, "base64") } : null;
}

// ---------- floors for the selector

export async function floorsOf(robot: string, places: Place[]): Promise<FloorInfo[]> {
  const store = getStore();
  const owner = isSimRobot(robot) ? REAL_ROBOT : robot;
  const reported = parse<MapInfo[]>(await store.getSetting(listKey(owner))) ?? [];
  const current = isSimRobot(robot) ? ((await store.getMap(robot))?.mapName ?? null) : await currentMapOf(robot);
  const names = new Map<string, MapInfo>();
  for (const m of reported) names.set(m.name, m);
  if (current && !names.has(current)) names.set(current, { name: current });
  for (const p of places) if (p.mapName && !names.has(p.mapName)) names.set(p.mapName, { name: p.mapName });
  const out: FloorInfo[] = [];
  for (const m of names.values()) {
    const spots = await rememberedSpots(owner, m.name);
    out.push({
      name: m.name,
      ...(m.floor !== undefined ? { floor: m.floor } : {}),
      current: m.name === current,
      image: Boolean(await mapImageMeta(robot, m.name)),
      spots: spots.map((s) => s.name),
    });
  }
  return out.sort((a, b) => (a.floor ?? 0) - (b.floor ?? 0) || a.name.localeCompare(b.name));
}

// ---------- no walks across floors (otto can't use the elevator)

function floorText(name: string) {
  return `"${name}"`;
}

// from = where the walk starts (reception or the place the patient is at), to = where it goes.
// the robot's current map counts for the real robot only (virtual otto is on whatever floor it walks)
export async function checkSameFloor(robot: string, from: Pick<Place, "name" | "mapName"> | undefined, to: Pick<Place, "name" | "mapName">) {
  if (from?.mapName && to.mapName && normalizeName(from.mapName) !== normalizeName(to.mapName)) {
    throw new ApiError(
      409,
      `${to.name} is on another floor (${floorText(to.mapName)}) than ${from.name} (${floorText(from.mapName)}). Otto can't take the elevator, so it can't walk this way`,
    );
  }
  if (!to.mapName || isSimRobot(robot)) return;
  const current = await currentMapOf(robot);
  if (current && normalizeName(current) !== normalizeName(to.mapName)) {
    throw new ApiError(
      409,
      `Otto is on the map ${floorText(current)} and ${to.name} is on ${floorText(to.mapName)}. Switch Otto to ${floorText(to.mapName)} on the Map page first, or pick a place on this floor`,
    );
  }
}
