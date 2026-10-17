import { json, requireRobot, routeWith } from "@/lib/http";
import { finishCommand } from "@/lib/robot";

export const dynamic = "force-dynamic";

// robot finished a command of any type (for a guide: back at reception or onto the next)
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  requireRobot(req);
  return json({ ok: true, command: await finishCommand(id) });
});
