import { json, readJson, requireRobot, routeWith } from "@/lib/http";
import { finishCommand } from "@/lib/robot";

export const dynamic = "force-dynamic";

// robot finished a command of any type (for a guide: back at reception or onto the next).
// body { ok, detail } = its answer for commands it can refuse or that can fail (switch_map, set_mode, recheck,
// a walk or words in show / calm mode); an empty body for the rest
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  requireRobot(req);
  return json({ ok: true, command: await finishCommand(id, await readJson(req)) });
});
