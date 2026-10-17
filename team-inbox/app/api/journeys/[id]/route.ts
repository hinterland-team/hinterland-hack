import { baseUrl, caller, json, readJson, routeWith } from "@/lib/http";
import { getJourney, patchJourney } from "@/lib/journeys";

export const dynamic = "force-dynamic";

export const GET = routeWith<{ id: string }>(async (req, { id }) => {
  await caller(req);
  return json(await getJourney(id));
});

// robot: { legId?, status?: pickup | walking | arrived | failed, event?: { type, detail? } }. staff: { status: "with_staff" }
export const PATCH = routeWith<{ id: string }>(async (req, { id }) => {
  const who = await caller(req);
  return json(await patchJourney(id, await readJson(req), who, baseUrl(req)));
});
