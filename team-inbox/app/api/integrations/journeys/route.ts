import { ApiError, baseUrl, json, readJson, route } from "@/lib/http";
import { createJourney } from "@/lib/journeys";
import { addHit, ipKey, overLimit } from "@/lib/rate";
import { sameText } from "@/lib/session";
import { getStore } from "@/lib/store";
import { normalizeName } from "@/lib/types";

export const dynamic = "force-dynamic";

const FIELDS = ["to", "pace", "wheelchair"];
const BAD_KEYS = 10;
const BAD_WINDOW_SECONDS = 600;

// the nurse's system: header X-Api-Key: <INTEGRATION_KEY>, body { to: placeId | place name, pace?, wheelchair? }.
// same as a journey from the reception board (a room with a waiting room: Nova walks to the waiting room).
// -> 201 { ticket, journeyId, to, via?, walkUrl }. nothing about the patient is taken
export const POST = route(async (req) => {
  const key = process.env.INTEGRATION_KEY?.trim();
  if (!key) throw new ApiError(404, "the integration is off (INTEGRATION_KEY is not set)");
  const ip = await ipKey(req, "integration");
  if (await overLimit(ip, BAD_KEYS, BAD_WINDOW_SECONDS)) {
    throw new ApiError(429, "too many wrong keys, try again later", { "Retry-After": String(BAD_WINDOW_SECONDS) });
  }
  if (!sameText(req.headers.get("x-api-key")?.trim() ?? "", key)) {
    await addHit(ip);
    throw new ApiError(401, "wrong or missing X-Api-Key");
  }

  const body = await readJson(req);
  const extra = Object.keys(body).filter((k) => !FIELDS.includes(k));
  if (extra.length) {
    throw new ApiError(400, `unknown field ${extra[0]}: Nova keeps no patient data, send only ${FIELDS.join(", ")}`);
  }
  const wanted = typeof body.to === "string" ? body.to.trim() : "";
  if (!wanted) throw new ApiError(400, "to is required (a place id or a place name)");
  const places = (await getStore().listPlaces()).filter((p) => p.active && p.kind !== "reception");
  const place =
    places.find((p) => p.id === wanted) ??
    places.find((p) => normalizeName(p.name) === normalizeName(wanted)) ??
    places.find((p) => normalizeName(p.spot) === normalizeName(wanted));
  if (!place) throw new ApiError(400, `no active place called ${wanted}`);
  const waiting = place.waitingPlaceId ? places.find((p) => p.id === place.waitingPlaceId && p.kind === "waiting") : undefined;

  const base = baseUrl(req);
  const { journey } = await createJourney(
    {
      to: waiting ? waiting.id : place.id,
      ...(waiting ? { forPlaceId: place.id } : {}),
      ...(body.pace !== undefined ? { pace: body.pace } : {}),
      ...(body.wheelchair !== undefined ? { wheelchair: body.wheelchair } : {}),
    },
    "staff",
    base,
    "integration",
  );
  return json(
    {
      ticket: journey.ticket,
      journeyId: journey.id,
      to: { name: place.name, ...(place.room ? { room: place.room } : {}) },
      ...(waiting ? { via: { name: waiting.name } } : {}),
      status: journey.status,
      ...(journey.walkCode ? { walkUrl: `${base}/walk/${journey.walkCode}` } : {}),
    },
    201,
  );
});
