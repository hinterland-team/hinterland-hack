import { NextResponse } from "next/server";
import { checkIn, withQueue, withQueueOne } from "@/lib/clinic";
import { baseUrl, flag, readJson, requireRobot, route, sinceParam } from "@/lib/http";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// robot checks a patient in. the visit comes back with its place in the doctor's line
export const POST = route(async (req) => {
  requireRobot(req);
  const result = await checkIn(await readJson(req), baseUrl(req));
  return NextResponse.json({ ...result, visit: await withQueueOne(result.visit) }, { status: 201 });
});

// dashboard: ?active=1 for open visits, ?since=<iso> for everything since then plus what is still open
export const GET = route(async (req) => {
  const url = new URL(req.url);
  const visits = await getStore().listVisits({ active: flag(url, "active"), since: sinceParam(url) });
  return NextResponse.json(await withQueue(visits));
});
