import { robotName } from "@/lib/common";
import { changeConfig, configView, robotConfig } from "@/lib/config";
import { caller, json, readJson, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// robot (bearer): { version, settings } with every setting (robot-setup.md section 3).
// dashboard: the same plus what was changed from the defaults and when otto applied which version
export const GET = route(async (req) => {
  const who = await caller(req);
  const robot = robotName(new URL(req.url).searchParams.get("robot"));
  return json(who === "robot" ? await robotConfig(robot) : await configView(robot));
});

// staff: { robot?, settings?: { walk: { speedNormal: 0.55 } }, reset?: "walk" | "all" }. a change makes a new version
// and sends config_changed to otto
export const PATCH = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  return json(await changeConfig(robotName(body.robot), body));
});
