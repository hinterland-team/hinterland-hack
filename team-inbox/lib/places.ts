import { MAX_PLACE_CHANNELS } from "./channel-types";
import { channelIdsKnown, forgetLast } from "./channels";
import { now, numberOrNull, retention } from "./common";
import { emailList } from "./email";
import { ApiError, text } from "./http";
import { maskUrl } from "./notify";
import { getStore } from "./store";
import type { PlacePatch } from "./store-types";
import {
  ALWAYS_PUBLIC,
  DEFAULT_MINUTES,
  MAX_MINUTES,
  MIN_MINUTES,
  PLACE_KINDS,
  type Place,
  type PlaceKind,
  ROOM_KINDS,
  currentLeg,
  isOneOf,
  normalizeName,
} from "./types";

function cleanWebhook(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  const url = text(value, 2000);
  if (!url) return null;
  if (!/^https?:\/\/\S+$/i.test(url)) throw new ApiError(400, "teamsWebhookUrl must be a http(s) link");
  return url;
}

// "a@x.de, b@y.de", at most 5. undefined = not sent, null = remove
function cleanEmail(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  const raw = text(value, 1300);
  if (!raw) return null;
  const list = emailList(raw);
  if (!list) throw new ApiError(400, "notifyEmail must be one email address, or up to 5 separated by commas");
  return list.join(", ");
}

// the channels a place picked on /notifications. undefined = not sent, null = back to the default channels
async function cleanChannelIds(value: unknown): Promise<string[] | null | undefined> {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (!Array.isArray(value) || value.length > MAX_PLACE_CHANNELS || value.some((v) => typeof v !== "string")) {
    throw new ApiError(400, `channelIds must be a list of at most ${MAX_PLACE_CHANNELS} channel ids, or null for the default channels`);
  }
  const known = await channelIdsKnown();
  const ids = [...new Set(value as string[])];
  const unknown = ids.find((id) => !known.has(id));
  if (unknown) throw new ApiError(400, `there is no channel ${unknown.slice(0, 80)}`);
  return ids;
}

// whole minutes 1-120. undefined = not sent
function minutes(value: unknown) {
  if (value === undefined) return undefined;
  const n = numberOrNull(value);
  if (n === null || n < MIN_MINUTES || n > MAX_MINUTES) {
    throw new ApiError(400, `avgMinutes must be a number from ${MIN_MINUTES} to ${MAX_MINUTES}`);
  }
  return Math.round(n);
}

function kindOf(value: unknown): PlaceKind | undefined {
  if (value === undefined) return undefined;
  if (!isOneOf(PLACE_KINDS, value)) throw new ApiError(400, `kind must be one of ${PLACE_KINDS.join(", ")}`);
  return value;
}

function bool(value: unknown, name: string) {
  if (value === undefined) return undefined;
  if (typeof value !== "boolean") throw new ApiError(400, `${name} must be true or false`);
  return value;
}

async function checkUnique(places: Place[], name: string, exceptId?: string) {
  const key = normalizeName(name);
  const clash = places.find((p) => p.id !== exceptId && normalizeName(p.name) === key);
  if (clash) throw new ApiError(409, `there is already a place called ${clash.name}`);
}

// otto picks patients up at one reception and guides them out through one exit
function checkSingle(places: Place[], kind: PlaceKind, active: boolean, exceptId?: string) {
  if (!active || (kind !== "reception" && kind !== "exit")) return;
  const other = places.find((p) => p.id !== exceptId && p.active && p.kind === kind);
  if (other) throw new ApiError(409, `${other.name} is already the ${kind}. Edit or deactivate it first`);
}

function checkWaiting(places: Place[], kind: PlaceKind, waitingPlaceId: string | null | undefined) {
  if (!waitingPlaceId) return;
  if (!ROOM_KINDS.includes(kind)) throw new ApiError(400, "only rooms can have a waiting room");
  const w = places.find((p) => p.id === waitingPlaceId);
  if (!w || w.kind !== "waiting") throw new ApiError(400, "waitingPlaceId must be a place of kind waiting");
}

// what leaves the server: the own teams webhook is a secret (anyone with it can post into the channel),
// so only a masked hint goes out. the dashboard sends a new link to replace it, or "" to remove it
export function publicPlace(p: Place): Place {
  return p.teamsWebhookUrl ? { ...p, teamsWebhookUrl: maskUrl(p.teamsWebhookUrl) } : p;
}

export async function listPlaces() {
  return (await getStore().listPlaces()).map(publicPlace);
}

export async function createPlace(body: Record<string, unknown>) {
  await retention();
  const store = getStore();
  const places = await store.listPlaces();
  const name = text(body.name, 80);
  if (!name || !normalizeName(name)) throw new ApiError(400, "name is required");
  await checkUnique(places, name);
  const kind = kindOf(body.kind) ?? "room";
  const active = bool(body.active, "active") ?? true;
  const waitingPlaceId = text(body.waitingPlaceId, 80);
  checkWaiting(places, kind, waitingPlaceId);
  const selfCheckIn = bool(body.selfCheckIn, "selfCheckIn") ?? false;
  if (selfCheckIn && !ROOM_KINDS.includes(kind)) throw new ApiError(400, "only rooms can be picked on the robot");
  checkSingle(places, kind, active);

  const place: Place = {
    id: crypto.randomUUID(),
    name,
    spot: text(body.spot, 80) ?? name,
    kind,
    public: ALWAYS_PUBLIC.includes(kind) || bool(body.public, "public") === true,
    avgMinutes: minutes(body.avgMinutes) ?? DEFAULT_MINUTES,
    active,
    selfCheckIn,
    createdAt: now(),
  };
  const room = text(body.room, 24);
  if (room) place.room = room;
  const webhook = cleanWebhook(body.teamsWebhookUrl);
  if (webhook) place.teamsWebhookUrl = webhook;
  if (waitingPlaceId) place.waitingPlaceId = waitingPlaceId;
  const via = text(body.viaSpot, 80);
  if (via) place.viaSpot = via;
  const email = cleanEmail(body.notifyEmail);
  if (email) place.notifyEmail = email;
  const channelIds = await cleanChannelIds(body.channelIds);
  if (channelIds) place.channelIds = channelIds;
  return publicPlace(await store.createPlace(place));
}

export async function updatePlace(body: Record<string, unknown>) {
  await retention();
  const id = text(body.id, 80);
  if (!id) throw new ApiError(400, "id is required");
  const store = getStore();
  const places = await store.listPlaces();
  const place = places.find((p) => p.id === id);
  if (!place) throw new ApiError(404, "place not found");

  const patch: PlacePatch = {};
  if (body.name !== undefined) {
    const name = text(body.name, 80);
    if (!name || !normalizeName(name)) throw new ApiError(400, "name can't be empty");
    await checkUnique(places, name, id);
    patch.name = name;
  }
  if (body.spot !== undefined) {
    const spot = text(body.spot, 80);
    if (!spot) throw new ApiError(400, "spot can't be empty");
    patch.spot = spot;
  }
  if (body.room !== undefined) patch.room = text(body.room, 24) ?? null;
  const kind = kindOf(body.kind) ?? place.kind;
  if (kind !== place.kind) patch.kind = kind;
  const active = bool(body.active, "active") ?? place.active;
  if (active !== place.active) patch.active = active;
  checkSingle(places, kind, active, id);
  const pub = bool(body.public, "public");
  if (ALWAYS_PUBLIC.includes(kind)) patch.public = true;
  else if (pub !== undefined) patch.public = pub;
  const m = minutes(body.avgMinutes);
  if (m !== undefined) patch.avgMinutes = m;
  // the masked hint sent back unchanged (an older page) keeps the saved link
  const maskedBack = Boolean(place.teamsWebhookUrl) && body.teamsWebhookUrl === maskUrl(place.teamsWebhookUrl ?? "");
  const webhook = maskedBack ? undefined : cleanWebhook(body.teamsWebhookUrl);
  if (webhook !== undefined) patch.teamsWebhookUrl = webhook;
  if (body.viaSpot !== undefined) patch.viaSpot = text(body.viaSpot, 80) ?? null;
  const email = cleanEmail(body.notifyEmail);
  if (email !== undefined) patch.notifyEmail = email;
  const channelIds = await cleanChannelIds(body.channelIds);
  if (channelIds !== undefined) patch.channelIds = channelIds;

  if (body.waitingPlaceId !== undefined) patch.waitingPlaceId = text(body.waitingPlaceId, 80) ?? null;
  const selfCheckIn = bool(body.selfCheckIn, "selfCheckIn");
  if (selfCheckIn !== undefined) patch.selfCheckIn = selfCheckIn;
  if (!ROOM_KINDS.includes(kind)) {
    // a reception, exit or waiting room has no waiting room of its own and is not a check-in choice
    if (patch.waitingPlaceId) throw new ApiError(400, "only rooms can have a waiting room");
    if (patch.selfCheckIn) throw new ApiError(400, "only rooms can be picked on the robot");
    if (place.waitingPlaceId) patch.waitingPlaceId = null;
    if (place.selfCheckIn) patch.selfCheckIn = false;
  }
  checkWaiting(places, kind, patch.waitingPlaceId);
  if (patch.waitingPlaceId === id) throw new ApiError(400, "a place can't be its own waiting room");

  const updated = await store.updatePlace(id, patch);
  if (!updated) throw new ApiError(404, "place not found");
  // a new own webhook or address: the status line of the old one goes
  if (patch.teamsWebhookUrl !== undefined && patch.teamsWebhookUrl !== (place.teamsWebhookUrl ?? null)) await forgetLast(`place:${id}:teams`);
  if (patch.notifyEmail !== undefined && patch.notifyEmail !== (place.notifyEmail ?? null)) await forgetLast(`place:${id}:email`);
  const out = publicPlace(updated);
  // no longer a waiting room: the rooms that used it walk straight in
  if (place.kind === "waiting" && kind !== "waiting") {
    for (const p of places) if (p.waitingPlaceId === id) await store.updatePlace(p.id, { waitingPlaceId: null });
  }
  return out;
}

export async function deletePlace(id: string | undefined) {
  await retention();
  if (!id) throw new ApiError(400, "id is required");
  const store = getStore();
  const open = await store.listJourneys({ active: true });
  const busy = open.find((j) => currentLeg(j).toPlaceId === id || j.forPlaceId === id);
  if (busy) throw new ApiError(409, `ticket ${busy.ticket} is still on its way there. Finish it first`);
  if (!(await store.deletePlace(id))) throw new ApiError(404, "place not found");
  return { ok: true };
}
