import { today } from "@/lib/common";
import { ApiError, caller, json, route } from "@/lib/http";
import { stats } from "@/lib/robot";

export const dynamic = "force-dynamic";

// only anonymous numbers. ?day=2026-10-17 or ?since=<iso>, default today in CLINIC_TIMEZONE
export const GET = route(async (req) => {
  await caller(req);
  const url = new URL(req.url);
  const day = url.searchParams.get("day");
  const since = url.searchParams.get("since");
  if (day && !/^\d{4}-\d{2}-\d{2}$/.test(day)) throw new ApiError(400, "day must look like 2026-10-17");
  if (since && Number.isNaN(Date.parse(since))) throw new ApiError(400, "since must be an ISO date");
  return json(await stats(day ?? (since ? today(Date.parse(since)) : today())));
});
