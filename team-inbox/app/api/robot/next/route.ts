import { NextResponse } from "next/server";
import { robotName } from "@/lib/common";
import { baseUrl, json, requireRobot, route } from "@/lib/http";
import { withWalkUrl } from "@/lib/journeys";
import { nextCommand } from "@/lib/robot";

export const dynamic = "force-dynamic";

// robot polls every 3 s: oldest undelivered command (guide, say, ask, go_to or walk_control) or 204.
// a guide also gets walkUrl, the phone page for its QR code on this server (PUBLIC_BASE_URL when set)
export const GET = route(async (req) => {
  requireRobot(req);
  const command = await nextCommand(robotName(new URL(req.url).searchParams.get("robot")));
  if (!command) return new NextResponse(null, { status: 204 });
  return json(withWalkUrl(command, baseUrl(req)));
});
