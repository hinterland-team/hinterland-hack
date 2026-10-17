import { json, readJson, requireStaff, routeWith } from "@/lib/http";
import { updateHelp } from "@/lib/robot";

export const dynamic = "force-dynamic";

// dashboard: { status: "done" } when someone took care of it
export const PATCH = routeWith<{ id: string }>(async (req, { id }) => {
  await requireStaff(req);
  return json(await updateHelp(id, (await readJson(req)).status));
});
