import { NextResponse } from "next/server";
import { createRobotCommand, robotName } from "@/lib/clinic";
import { readJson, route } from "@/lib/http";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// dashboard: { robot, type: "say", text } or { robot, type: "go_to", spot }. the robot gets it from /api/robot/next
export const POST = route(async (req) => {
  const command = await createRobotCommand(await readJson(req));
  return NextResponse.json(command, { status: 201 });
});

// dashboard: last commands of one robot, newest first. ?robot=nova&limit=10
export const GET = route(async (req) => {
  const url = new URL(req.url);
  const limit = Math.min(50, Math.max(1, Math.round(Number(url.searchParams.get("limit"))) || 10));
  return NextResponse.json(await getStore().listCommands(robotName(url.searchParams.get("robot")), limit));
});
