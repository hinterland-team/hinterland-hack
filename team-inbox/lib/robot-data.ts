// checks and cleans what the robot sends for logs and the live map. pure functions, no store
import {
  LOG_KEEP_HOURS,
  LOG_LEVELS,
  LOG_SOURCES,
  type LogLevel,
  type LogSource,
  MAP_MAPS_MAX,
  MAP_PLACES_MAX,
  MAP_SCAN_MAX,
  type MapFollower,
  type MapInfo,
  type MapPose,
  type MapSpot,
  isOneOf,
} from "./types";

export const LOG_ITEMS_MAX = 200;
export const LOG_TEXT_MAX = 1000;
// map coordinates are meters. anything further out is a bug, not a building
const MAP_LIMIT = 10_000;

export type NewLog = { at: string; level: LogLevel; source: LogSource; text: string };

export type MapFrame = {
  robot: string;
  mapName?: string;
  pose: MapPose;
  // undefined = not sent this time, keep the ones from before
  places?: MapSpot[];
  scan: [number, number][];
  follower?: MapFollower;
  target?: MapSpot;
  remainingMeters?: number;
  // undefined = not sent this time
  maps?: MapInfo[];
  at: string;
};

const LEVEL_ALIASES: Record<string, LogLevel> = {
  warning: "warn",
  err: "error",
  fatal: "error",
  assert: "error",
  verbose: "debug",
  trace: "debug",
};

function str(value: unknown, max: number) {
  if (typeof value !== "string") return undefined;
  const t = value.trim().replace(/\s+/g, " ");
  return t ? t.slice(0, max) : undefined;
}

function num(value: unknown) {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}

const cm = (n: number) => Math.round(n * 100) / 100;

function coord(value: unknown) {
  const n = num(value);
  return n === null || Math.abs(n) > MAP_LIMIT ? null : cm(n);
}

function point(value: unknown): [number, number] | null {
  if (Array.isArray(value)) {
    const x = coord(value[0]);
    const y = coord(value[1]);
    return x === null || y === null ? null : [x, y];
  }
  if (value && typeof value === "object") {
    const o = value as Record<string, unknown>;
    const x = coord(o.x);
    const y = coord(o.y);
    return x === null || y === null ? null : [x, y];
  }
  return null;
}

// a robot time when it is plausible (last 24 h, not in the future), else the server's
export function logTime(value: unknown, now: number) {
  if (typeof value !== "string" && typeof value !== "number") return new Date(now).toISOString();
  const t = typeof value === "number" ? value : Date.parse(value);
  if (!Number.isFinite(t) || t > now + 60_000 || t < now - LOG_KEEP_HOURS * 3600_000) return new Date(now).toISOString();
  return new Date(t).toISOString();
}

export function logLevel(value: unknown): LogLevel {
  const v = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (isOneOf(LOG_LEVELS, v)) return v;
  return LEVEL_ALIASES[v] ?? "info";
}

export function logSource(value: unknown): LogSource {
  const v = typeof value === "string" ? value.trim().toLowerCase() : "";
  return isOneOf(LOG_SOURCES, v) ? v : "app";
}

// odd levels and sources are mapped (a log line is never refused for that), empty lines are dropped
export function cleanLogItems(raw: unknown, now: number): { items: NewLog[]; dropped: number } | { error: string } {
  if (!Array.isArray(raw)) return { error: "items must be a list" };
  let dropped = Math.max(0, raw.length - LOG_ITEMS_MAX);
  const items: NewLog[] = [];
  for (const r of raw.slice(0, LOG_ITEMS_MAX)) {
    const o = r && typeof r === "object" ? (r as Record<string, unknown>) : null;
    const text = o ? str(o.text, LOG_TEXT_MAX) : undefined;
    if (!o || !text) {
      dropped++;
      continue;
    }
    items.push({ at: logTime(o.at, now), level: logLevel(o.level), source: logSource(o.source), text });
  }
  return { items, dropped };
}

// keeps every n-th point so the scan still covers all angles
export function thin<T>(list: T[], max: number) {
  if (list.length <= max) return list;
  const out: T[] = [];
  const step = list.length / max;
  for (let i = 0; i < max; i++) out.push(list[Math.floor(i * step)]);
  return out;
}

function angle(value: number) {
  const a = Math.atan2(Math.sin(value), Math.cos(value));
  return Math.round(a * 1000) / 1000;
}

function spot(value: unknown, fallbackName?: string): MapSpot | null {
  if (!value || typeof value !== "object") return null;
  const o = value as Record<string, unknown>;
  const name = str(o.name, 80) ?? fallbackName;
  const p = point(o);
  return name && p ? { name, x: p[0], y: p[1] } : null;
}

export function cleanMapFrame(body: Record<string, unknown>, robot: string, now: string): { frame: MapFrame } | { error: string } {
  const p = body.pose && typeof body.pose === "object" ? (body.pose as Record<string, unknown>) : null;
  const xy = p ? point(p) : null;
  const theta = p ? num(p.theta) : null;
  if (!xy || theta === null) return { error: "pose needs x, y and theta (map frame, meters and radians)" };

  const frame: MapFrame = {
    robot,
    pose: { x: xy[0], y: xy[1], theta: angle(theta) },
    scan: [],
    at: now,
  };
  const mapName = str(body.mapName, 80);
  if (mapName) frame.mapName = mapName;

  if (body.places !== undefined && body.places !== null) {
    if (!Array.isArray(body.places)) return { error: "places must be a list of { name, x, y }" };
    const seen = new Set<string>();
    const places: MapSpot[] = [];
    for (const r of body.places) {
      const s = spot(r);
      if (!s || seen.has(s.name)) continue;
      seen.add(s.name);
      places.push(s);
      if (places.length >= MAP_PLACES_MAX) break;
    }
    frame.places = places;
  }

  if (body.scan !== undefined && body.scan !== null) {
    if (!Array.isArray(body.scan)) return { error: "scan must be a list of [x, y] points" };
    frame.scan = thin(
      body.scan.map(point).filter((v): v is [number, number] => v !== null),
      MAP_SCAN_MAX,
    );
  }

  if (body.follower && typeof body.follower === "object") {
    const f = body.follower as Record<string, unknown>;
    const fp = point(f);
    if (fp) frame.follower = { x: fp[0], y: fp[1], source: f.source === "camera" ? "camera" : "lidar" };
  }

  const target = spot(body.target, "Target");
  if (target) frame.target = target;

  const left = num(body.remainingMeters);
  if (left !== null && left >= 0 && left < MAP_LIMIT) frame.remainingMeters = Math.round(left * 10) / 10;

  const maps = cleanMaps(body.maps);
  if (maps) frame.maps = maps;

  return { frame };
}

// the robot's floor maps: [{ name, floor? }] (plain names are taken too). in the map frame and the heartbeat
export function cleanMaps(raw: unknown): MapInfo[] | undefined {
  if (!Array.isArray(raw)) return undefined;
  const seen = new Set<string>();
  const maps: MapInfo[] = [];
  for (const r of raw) {
    const o = typeof r === "string" ? { name: r } : r && typeof r === "object" ? (r as Record<string, unknown>) : null;
    const name = o ? str(o.name, 80) : undefined;
    if (!name || seen.has(name)) continue;
    seen.add(name);
    const floor = o ? num(o.floor) : null;
    maps.push(floor !== null && Number.isInteger(floor) && Math.abs(floor) <= 200 ? { name, floor } : { name });
    if (maps.length >= MAP_MAPS_MAX) break;
  }
  return maps;
}

// ---------- the floor plan image (robot-setup.md section 1)

export const MAP_IMAGE_MAX_BYTES = 6 * 1024 * 1024;
export const MAP_IMAGE_MAX_SIDE = 8192;

export type MapImageUpload = {
  mapName: string;
  width: number;
  height: number;
  resolution: number;
  originX: number;
  originY: number;
  hash: string;
  png: string;
  bytes: number;
};

// width and height of a png, read from its IHDR chunk. null when it is not a png
export function pngSize(bytes: Uint8Array): { width: number; height: number } | null {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length < 24 || sig.some((b, i) => bytes[i] !== b)) return null;
  if (String.fromCharCode(bytes[12], bytes[13], bytes[14], bytes[15]) !== "IHDR") return null;
  const u32 = (o: number) => ((bytes[o] << 24) | (bytes[o + 1] << 16) | (bytes[o + 2] << 8) | bytes[o + 3]) >>> 0;
  return { width: u32(16), height: u32(20) };
}

// { robot, mapName, width, height, resolution, originX, originY, png: base64, hash }. the png's own size wins over
// width / height (the robot may have scaled it down, the resolution says how many meters one pixel is)
export function cleanMapImage(body: Record<string, unknown>): { image: MapImageUpload } | { error: string } {
  const mapName = str(body.mapName, 80);
  if (!mapName) return { error: "mapName is required" };
  const resolution = num(body.resolution);
  if (resolution === null || resolution <= 0 || resolution > 10) return { error: "resolution must be meters per pixel, above 0" };
  const originX = num(body.originX);
  const originY = num(body.originY);
  if (originX === null || originY === null || Math.abs(originX) > MAP_LIMIT || Math.abs(originY) > MAP_LIMIT) {
    return { error: "originX and originY must be the map position of pixel 0,0 in meters" };
  }
  if (typeof body.png !== "string" || !body.png.trim()) return { error: "png must be the image as base64" };
  const b64 = body.png.trim().replace(/^data:image\/png;base64,/, "").replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(b64)) return { error: "png must be base64" };
  const raw = Buffer.from(b64, "base64");
  if (raw.length > MAP_IMAGE_MAX_BYTES) return { error: `the png is larger than ${MAP_IMAGE_MAX_BYTES / 1024 / 1024} MB` };
  const size = pngSize(raw);
  if (!size) return { error: "png is not a png image" };
  if (!size.width || !size.height || size.width > MAP_IMAGE_MAX_SIDE || size.height > MAP_IMAGE_MAX_SIDE) {
    return { error: `the image must be 1 to ${MAP_IMAGE_MAX_SIDE} pixels on each side` };
  }
  const hash = str(body.hash, 128)?.replace(/[^A-Za-z0-9._:-]/g, "") || undefined;
  return {
    image: {
      mapName,
      width: size.width,
      height: size.height,
      resolution,
      originX,
      originY,
      hash: hash ?? `b${raw.length}-${b64.slice(-16)}`,
      png: b64,
      bytes: raw.length,
    },
  };
}
