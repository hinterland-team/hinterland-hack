import { baseUrl, caller, flag, json, readJson, route } from "@/lib/http";
import { createJourney, listJourneys } from "@/lib/journeys";

export const dynamic = "force-dynamic";

// dashboard, or the robot for public places and self check-in rooms.
// { to, forPlaceId?, pace?, wheelchair?, robot? } -> 201 { journey, command }
export const POST = route(async (req) => {
  const who = await caller(req);
  return json(await createJourney(await readJson(req), who, baseUrl(req)), 201);
});

// ?active=1 for the open ones. newest first
export const GET = route(async (req) => {
  await caller(req);
  return json(await listJourneys(flag(new URL(req.url), "active")));
});
