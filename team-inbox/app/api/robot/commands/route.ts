import { robotName } from "@/lib/common";
import { caller, json, readJson, requireStaff, route } from "@/lib/http";
import { createRobotCommand, listCommands } from "@/lib/robot";
import { staffWalkControl } from "@/lib/walk";

export const dynamic = "force-dynamic";

// dashboard: { robot, type: "ask" | "say", text }, { robot, type: "go_to", spot }
// or { type: "walk_control", journeyId, action: wait | resume | slower | faster }. the robot gets it from /api/robot/next
export const POST = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  if (body.type === "walk_control") return json(await staffWalkControl(body), 201);
  return json(await createRobotCommand(body), 201);
});

// dashboard: last commands of one robot, newest first. ?robot=nova&limit=10
export const GET = route(async (req) => {
  await caller(req);
  const url = new URL(req.url);
  return json(await listCommands(robotName(url.searchParams.get("robot")), Number(url.searchParams.get("limit")) || 10));
});
