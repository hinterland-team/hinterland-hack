import { caller, json, jsonEtag, readJson, requireRobot, route } from "@/lib/http";
import { mapView, postMap } from "@/lib/map";

export const dynamic = "force-dynamic";

// robot: { robot, mapName?, pose, places?, scan?, follower?, target?, remainingMeters?, maps? }. keeps the latest + a trail
export const POST = route(async (req) => {
  requireRobot(req);
  return json(await postMap(await readJson(req)));
});

// dashboard: { map: latest frame with trail, or null, image?: the floor plan's numbers, floors?: the maps otto knows }.
// 304 while nothing changed
export const GET = route(async (req) => {
  await caller(req);
  return jsonEtag(req, await mapView(new URL(req.url).searchParams.get("robot") ?? ""));
});
