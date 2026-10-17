// where virtual otto walks (/simulate): the real spots of otto's map when the robot sent them, else a simple
// floor laid out from the places on the Places page (marked as made up). pure, no imports but types (tests load it)
import type { MapSpot, Place } from "./types";
import { normalizeName } from "./types";

export type SimSpot = { name: string; x: number; y: number; placeId?: string; generated?: boolean };
export type SimFloor = { mapName: string; real: boolean; spots: SimSpot[] };

const KIND_ORDER = ["reception", "waiting", "room", "other", "exit"];

// places in a calm order: reception first, then waiting rooms and rooms (by name), the exit last
function ordered(places: Place[]) {
  return [...places]
    .filter((p) => p.active)
    .sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || a.name.localeCompare(b.name));
}

// a made-up corridor: reception at 0,0, rooms along the corridor on both sides, each waiting room next to its room,
// the exit behind the reception
function generated(places: Place[]): SimSpot[] {
  const out: SimSpot[] = [];
  const list = ordered(places);
  const rooms = list.filter((p) => p.kind === "room" || p.kind === "other");
  const waiting = list.filter((p) => p.kind === "waiting");
  for (const p of list.filter((x) => x.kind === "reception")) out.push({ name: p.spot, x: 0, y: 0, placeId: p.id, generated: true });
  rooms.forEach((p, i) => out.push({ name: p.spot, x: 6 + i * 5, y: i % 2 === 0 ? 3.5 : -3.5, placeId: p.id, generated: true }));
  waiting.forEach((w, i) => {
    const room = out.find((s) => rooms.some((r) => r.id === s.placeId && r.waitingPlaceId === w.id));
    out.push({
      name: w.spot,
      x: room ? room.x - 2.5 : 3 + i * 5,
      y: room ? room.y * 0.35 : 0,
      placeId: w.id,
      generated: true,
    });
  });
  list.filter((x) => x.kind === "exit").forEach((p, i) => out.push({ name: p.spot, x: -5 - i * 3, y: 0, placeId: p.id, generated: true }));
  return out;
}

export function simFloor(places: Place[], real: { mapName?: string; places: MapSpot[] } | null): SimFloor {
  const active = places.filter((p) => p.active);
  if (!real || real.places.length === 0) return { mapName: "Simulated floor", real: false, spots: generated(active) };
  // the floor otto is on: places of other floors are left out
  const here = active.filter((p) => !p.mapName || !real.mapName || normalizeName(p.mapName) === normalizeName(real.mapName));
  const byName = new Map(real.places.map((s) => [normalizeName(s.name), s]));
  const spots: SimSpot[] = [];
  const missing: Place[] = [];
  for (const p of ordered(here)) {
    const s = byName.get(normalizeName(p.spot)) ?? byName.get(normalizeName(p.name));
    if (s) spots.push({ name: p.spot, x: s.x, y: s.y, placeId: p.id });
    else missing.push(p);
  }
  // places the robot's map does not have yet: in a row next to the floor, marked as made up
  const maxX = Math.max(0, ...real.places.map((s) => s.x));
  const minY = Math.min(0, ...real.places.map((s) => s.y));
  missing.forEach((p, i) => spots.push({ name: p.spot, x: maxX + 3 + i * 3, y: minY - 2, placeId: p.id, generated: true }));
  // the other spots of the real map, for orientation
  for (const s of real.places) if (!spots.some((x) => normalizeName(x.name) === normalizeName(s.name))) spots.push({ name: s.name, x: s.x, y: s.y });
  return { mapName: real.mapName ?? "Otto's map", real: true, spots };
}

export function spotOf(floor: SimFloor, name: string | undefined) {
  if (!name) return undefined;
  const key = normalizeName(name);
  return floor.spots.find((s) => normalizeName(s.name) === key);
}

// the walk as straight lines: from, optional detour points, to
export function pathLength(points: { x: number; y: number }[]) {
  let d = 0;
  for (let i = 1; i < points.length; i++) d += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
  return d;
}

// the point `dist` metres along the path, and the heading there
export function along(points: { x: number; y: number }[], dist: number) {
  let left = Math.max(0, dist);
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const seg = Math.hypot(b.x - a.x, b.y - a.y);
    const theta = Math.atan2(b.y - a.y, b.x - a.x);
    if (left <= seg || i === points.length - 1) {
      const t = seg === 0 ? 1 : Math.min(1, left / seg);
      return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t, theta };
    }
    left -= seg;
  }
  const last = points[points.length - 1];
  return { x: last.x, y: last.y, theta: 0 };
}
