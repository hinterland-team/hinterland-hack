import { caller, json, readJson, requireRobot, route } from "@/lib/http";
import { postLogs, readLogs } from "@/lib/logs";

export const dynamic = "force-dynamic";

// robot: { robot, items: [{ at, level, source, text }] } -> { ok, stored, dropped }. never patient words
export const POST = route(async (req) => {
  requireRobot(req);
  return json(await postLogs(await readJson(req)));
});

// dashboard: ?robot=nova&level=warn&source=nav,voice&q=&after=<id>|before=<id>&limit= -> { lines (oldest first), more }
export const GET = route(async (req) => {
  await caller(req);
  return json(await readLogs(new URL(req.url)));
});
