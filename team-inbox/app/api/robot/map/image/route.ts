import { NextResponse } from "next/server";
import { robotName } from "@/lib/common";
import { mapImagePng } from "@/lib/floors";
import { ApiError, caller, readJson, requireRobot, route, text } from "@/lib/http";
import { postMapImage } from "@/lib/map";

export const dynamic = "force-dynamic";

// robot (robot-setup.md section 1): { robot, mapName, width, height, resolution, originX, originY, png: base64, hash }.
// only when the floor plan changed. -> 204
export const POST = route(async (req) => {
  requireRobot(req);
  await postMapImage(await readJson(req));
  return new NextResponse(null, { status: 204 });
});

// dashboard: the png of one map. ?robot=nova&map=<map name>&v=<hash> (with v it can be cached for good)
export const GET = route(async (req) => {
  await caller(req);
  const url = new URL(req.url);
  const map = text(url.searchParams.get("map"), 80);
  if (!map) throw new ApiError(400, "map is required");
  const found = await mapImagePng(robotName(url.searchParams.get("robot")), map);
  if (!found) throw new ApiError(404, "no floor plan for this map yet");
  const tag = `"${found.meta.hash}"`;
  const cache = url.searchParams.get("v") === found.meta.hash ? "private, max-age=31536000, immutable" : "private, no-cache";
  if (req.headers.get("if-none-match") === tag) return new NextResponse(null, { status: 304, headers: { ETag: tag, "Cache-Control": cache } });
  return new NextResponse(new Uint8Array(found.png), {
    status: 200,
    headers: { "Content-Type": "image/png", "Content-Length": String(found.png.length), ETag: tag, "Cache-Control": cache },
  });
});
