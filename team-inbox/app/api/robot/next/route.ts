import { NextResponse } from "next/server";
import { robotName } from "@/lib/common";
import { json, requireRobot, route } from "@/lib/http";
import { nextCommand } from "@/lib/robot";

export const dynamic = "force-dynamic";

// robot polls every 3 s: oldest undelivered command (guide, say, ask or go_to) or 204
export const GET = route(async (req) => {
  requireRobot(req);
  const command = await nextCommand(robotName(new URL(req.url).searchParams.get("robot")));
  if (!command) return new NextResponse(null, { status: 204 });
  return json(command);
});
