import { robotName } from "@/lib/common";
import { caller, json, route } from "@/lib/http";
import { getRobotStatus } from "@/lib/robot";

export const dynamic = "force-dynamic";

// dashboard: latest heartbeat (with the screen mirror) plus online (heartbeat or poll in the last 45 s)
export const GET = route(async (req) => {
  await caller(req);
  return json(await getRobotStatus(robotName(new URL(req.url).searchParams.get("robot"))));
});
