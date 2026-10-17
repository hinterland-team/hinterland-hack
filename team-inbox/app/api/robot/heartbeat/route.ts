import { NextResponse } from "next/server";
import { saveHeartbeat } from "@/lib/clinic";
import { readJson, requireRobot, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// robot, every 15 s: { robot, battery, charging, state, position, goal, detail? }. keeps only the latest
export const POST = route(async (req) => {
  requireRobot(req);
  await saveHeartbeat(await readJson(req));
  return new NextResponse(null, { status: 204 });
});
