import { baseUrl, caller, flag, json, readJson, requireRobot, route } from "@/lib/http";
import { requestHelp } from "@/lib/robot";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// robot asks a person for help: { robot, summary, urgency, journeyId?, ticket? } -> teams message to the practice channel
export const POST = route(async (req) => {
  requireRobot(req);
  const { help, notified } = await requestHelp(await readJson(req), baseUrl(req));
  return json({ ok: true, id: help.id, notified, help }, 201);
});

// dashboard (and the robot, to see if its request was taken): ?open=1 for the ones nobody took yet
export const GET = route(async (req) => {
  await caller(req);
  return json(await getStore().listHelp({ open: flag(new URL(req.url), "open") }));
});
