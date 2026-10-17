import { board } from "@/lib/board";
import { robotName } from "@/lib/common";
import { jsonEtag, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// dashboard: everything the reception and room pages show, in one request.
// polled every 2 s: If-None-Match with the last ETag gets 304 without a body while nothing changed
export const GET = route(async (req) => {
  await requireStaff(req);
  const data = await board(robotName(new URL(req.url).searchParams.get("robot")));
  const { now: _clock, ...content } = data;
  void _clock;
  return jsonEtag(req, data, content);
});
