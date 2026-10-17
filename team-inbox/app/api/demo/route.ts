import { robotName } from "@/lib/common";
import { demoView } from "@/lib/demo-view";
import { json, jsonEtag, readJson, requestOrigin, requireStaff, route } from "@/lib/http";
import { createRobotCommand } from "@/lib/robot";
import { isSimRobot } from "@/lib/robots";
import { simIsOn, simOn } from "@/lib/sim";

export const dynamic = "force-dynamic";

// staff, /demo: otto's demo state, the script and otto's answers. 304 while nothing changed
export const GET = route(async (req) => {
  await requireStaff(req);
  return jsonEtag(req, await demoView(robotName(new URL(req.url).searchParams.get("robot"))));
});

// staff: { robot?, op: start | next | hold | resume | restart | stop | say, text? } -> the demo command for otto.
// start carries the saved script. virtual otto is switched on for a start
export const POST = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  const robot = robotName(body.robot);
  if (isSimRobot(robot) && body.op === "start" && !simIsOn()) await simOn(requestOrigin(req));
  return json(await createRobotCommand({ type: "demo", robot, op: body.op, text: body.text }), 201);
});
