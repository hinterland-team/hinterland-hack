import { robotName } from "@/lib/common";
import { modeView, setMode } from "@/lib/config";
import { json, jsonEtag, readJson, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// staff, the switch at the top of the dashboard: { robot, mode (saved), applied: { mode, at } | null, online }
export const GET = route(async (req) => {
  await requireStaff(req);
  return jsonEtag(req, await modeView(robotName(new URL(req.url).searchParams.get("robot"))));
});

// { robot?, mode: normal | show | calm } -> saved as settings.mode, set_mode goes to otto
export const POST = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  return json(await setMode(robotName(body.robot), body.mode));
});
