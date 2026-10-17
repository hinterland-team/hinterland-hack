import { NextResponse } from "next/server";
import { nextCommand, robotName } from "@/lib/clinic";
import { requireRobot, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// robot polls every few seconds. oldest undelivered command (call_patient, say or go_to) or 204
export const GET = route(async (req) => {
  requireRobot(req);
  const command = await nextCommand(robotName(new URL(req.url).searchParams.get("robot")));
  if (!command) return new NextResponse(null, { status: 204 });
  return NextResponse.json(command);
});
