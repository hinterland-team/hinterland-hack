import { json, readJson, requireStaff, route, text } from "@/lib/http";
import { createPlace, deletePlace, listPlaces, updatePlace } from "@/lib/places";

export const dynamic = "force-dynamic";

// dashboard and robot: all places (the robot reads spots and public / self check-in places)
export const GET = route(async () => json(await listPlaces()));

// { name, room?, spot?, kind, public?, avgMinutes?, teamsWebhookUrl?, notifyEmail?, waitingPlaceId?, selfCheckIn?, active?, channelIds? }
export const POST = route(async (req) => {
  await requireStaff(req);
  return json(await createPlace(await readJson(req)), 201);
});

// { id, ...fields }. room / teamsWebhookUrl / waitingPlaceId: "" removes it. channelIds: null = the default channels
export const PATCH = route(async (req) => {
  await requireStaff(req);
  return json(await updatePlace(await readJson(req)));
});

// ?id=<placeId> or { id }
export const DELETE = route(async (req) => {
  await requireStaff(req);
  const id = new URL(req.url).searchParams.get("id") ?? text((await readJson(req)).id, 80);
  return json(await deletePlace(id ?? undefined));
});
