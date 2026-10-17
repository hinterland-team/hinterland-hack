import { board } from "@/lib/board";
import { robotName } from "@/lib/common";
import { json, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// dashboard: everything the reception and room pages show, in one request
export const GET = route(async (req) => {
  await requireStaff(req);
  return json(await board(robotName(new URL(req.url).searchParams.get("robot"))));
});
