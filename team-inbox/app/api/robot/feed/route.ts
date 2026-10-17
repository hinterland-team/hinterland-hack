import { robotName } from "@/lib/common";
import { caller, json, readJson, requireRobot, requireStaff, route } from "@/lib/http";
import { getFeed, postFeed, setShowPatientText } from "@/lib/robot";

export const dynamic = "force-dynamic";

// robot: { robot, journeyId?, items: [{ at, who, kind, text }] } -> { ok, stored, dropped, showPatientText }. memory only
export const POST = route(async (req) => {
  requireRobot(req);
  return json(await postFeed(await readJson(req)));
});

// dashboard: newest first, plus the demo switch
export const GET = route(async (req) => {
  await caller(req);
  const url = new URL(req.url);
  return json(await getFeed(robotName(url.searchParams.get("robot")), Number(url.searchParams.get("limit")) || 50));
});

// dashboard: { showPatientText: boolean } = the "Show what patients say (demo)" switch
export const PATCH = route(async (req) => {
  await requireStaff(req);
  return json({ showPatientText: await setShowPatientText((await readJson(req)).showPatientText) });
});
