import { robotName } from "@/lib/common";
import { caller, json, readJson, requireStaff, route } from "@/lib/http";
import { createRobotCommand, listCommands } from "@/lib/robot";

export const dynamic = "force-dynamic";

// dashboard: { robot, type: "ask" | "say", text } or { robot, type: "go_to", spot }. the robot gets it from /api/robot/next
export const POST = route(async (req) => {
  await requireStaff(req);
  return json(await createRobotCommand(await readJson(req)), 201);
});

// dashboard: last commands of one robot, newest first. ?robot=nova&limit=10
export const GET = route(async (req) => {
  await caller(req);
  const url = new URL(req.url);
  return json(await listCommands(robotName(url.searchParams.get("robot")), Number(url.searchParams.get("limit")) || 10));
});
