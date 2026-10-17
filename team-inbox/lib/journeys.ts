import { notifyPlace } from "./channels";
import { event, now, retention, robotName, today } from "./common";
import { assertCanGuide } from "./config";
import { checkSameFloor } from "./floors";
import { ApiError, type Caller, text } from "./http";
import { clearJourneyFeed } from "./live-feed";
import { type CardLinks, onTheWayMessage, placeLabel, robotLabel, visitorMessage } from "./messages";
import { isSimRobot } from "./robots";
import { getStore } from "./store";
import type { JourneyChange, StoredJourney } from "./store-types";
import {
  type Counters,
  FINAL_LEG_STATUSES,
  type GuideCommand,
  type Journey,
  type JourneyEvent,
  type JourneyWithQueue,
  type Leg,
  type LegStatus,
  PACES,
  type Place,
  type RobotCommand,
  ROBOT_LEG_STATUSES,
  ROOM_KINDS,
  currentLeg,
  isClosed,
  isOneOf,
  lineKey,
  normalizeTicket,
  pickTicket,
  queueFor,
  statusForLeg,
  walkCodeFrom,
} from "./types";

// the only fields a new journey takes. anything else (a name, a note) is refused, not ignored
const JOURNEY_FIELDS = ["to", "forPlaceId", "pace", "wheelchair", "robot"];
const EVENT_TYPE = /^[a-z][a-z_]{1,31}$/;

// ---------- reading

function strip(j: StoredJourney): Journey {
  const out: Journey & { version?: number } = { ...j };
  delete out.version;
  return out;
}

// adds queuePosition and estimatedWaitMinutes, computed from the open journeys right now
export async function withQueue(list: StoredJourney[], open?: StoredJourney[]): Promise<JourneyWithQueue[]> {
  const store = getStore();
  const [openList, places] = await Promise.all([open ?? store.listJourneys({ active: true }), store.listPlaces()]);
  const place = queueFor(openList, places);
  return list.map((j) => ({ ...strip(j), ...place(j) }));
}

export async function withQueueOne(j: StoredJourney) {
  return (await withQueue([j]))[0];
}

export async function listJourneys(active: boolean) {
  const list = await getStore().listJourneys({ active });
  return withQueue(list, active ? list : undefined);
}

async function mustGet(id: string) {
  const j = await getStore().getJourney(id);
  if (!j) throw new ApiError(404, "journey not found");
  return j;
}

export async function getJourney(id: string) {
  return withQueueOne(await mustGet(id));
}

export async function journeyByTicket(raw: string) {
  const ticket = normalizeTicket(raw);
  if (!ticket) throw new ApiError(400, "a ticket is one letter and two digits, like A17");
  const j = await getStore().findOpenByTicket(ticket);
  if (!j) throw new ApiError(404, "no open journey with that ticket");
  return withQueueOne(j);
}

// ---------- writing helpers

// reads, computes the change and saves it only if nobody changed the journey meanwhile
async function mutate(id: string, compute: (j: StoredJourney) => JourneyChange | null) {
  const store = getStore();
  for (let attempt = 0; attempt < 4; attempt++) {
    const j = await mustGet(id);
    const change = compute(j);
    if (!change) return { journey: j, before: j, changed: false };
    const saved = await store.saveJourney(id, j.version, change);
    if (saved) return { journey: saved, before: j, changed: true };
  }
  throw new ApiError(409, "the journey changed at the same time, please try again");
}

async function addEvents(id: string, events: JourneyEvent[]) {
  if (!events.length) return;
  await mutate(id, (j) => (isClosed(j.status) ? null : { updatedAt: now(), events }));
}

export const addJourneyEvents = addEvents;

// anonymous counters for today's numbers. walks of the simulation (Virtual Otto) are not counted
async function bump(robot: string, placeId: string, delta: Partial<Counters>) {
  if (isSimRobot(robot)) return;
  await getStore().bumpStats(today(), placeId, delta);
}

async function activePlaces() {
  return (await getStore().listPlaces()).filter((p) => p.active);
}

function mustPlace(places: Place[], id: unknown, what: string) {
  const key = text(id, 80);
  if (!key) throw new ApiError(400, `${what} is required`);
  const place = places.find((p) => p.id === key);
  if (!place) throw new ApiError(400, `${what} is not an active place`);
  return place;
}

function newLegTo(to: Place, from: { id: string | null; name?: string; spot?: string }, at: string): Leg {
  const leg: Leg = {
    id: crypto.randomUUID(),
    fromPlaceId: from.id,
    toPlaceId: to.id,
    toName: to.name,
    toSpot: to.spot,
    status: "queued",
    createdAt: at,
  };
  if (from.name) leg.fromName = from.name;
  if (from.spot) leg.fromSpot = from.spot;
  if (to.room) leg.toRoom = to.room;
  return leg;
}

// every guide command that leaves the server carries the absolute phone page link (the robot's qr code)
export function withWalkUrl<C extends RobotCommand>(command: C, base: string): C {
  if (command.type !== "guide" || !command.walkCode) return command;
  return { ...command, walkUrl: `${base}/walk/${command.walkCode}` };
}

// 8 characters for the patient's phone page
export function newWalkCode() {
  for (;;) {
    const code = walkCodeFrom(crypto.getRandomValues(new Uint8Array(24)));
    if (code) return code;
  }
}

async function queueGuide(j: Journey, leg: Leg) {
  // the detour spot of the destination, for when the normal way is blocked
  const place = await getStore().getPlace(leg.toPlaceId);
  const command: GuideCommand = {
    id: crypto.randomUUID(),
    robot: j.robot,
    type: "guide",
    journeyId: j.id,
    legId: leg.id,
    ticket: j.ticket,
    pace: j.pace,
    wheelchair: j.wheelchair,
    from: { name: leg.fromName ?? leg.toName, spot: leg.fromSpot ?? leg.toSpot },
    to: {
      name: leg.toName,
      spot: leg.toSpot,
      ...(leg.toRoom ? { room: leg.toRoom } : {}),
      ...(place?.viaSpot ? { viaSpot: place.viaSpot } : {}),
    },
    ...(j.walkCode ? { walkCode: j.walkCode } : {}),
    createdAt: now(),
  };
  return getStore().createCommand(command);
}

function links(base: string, j: Journey, places: Place[]): CardLinks {
  const key = process.env.CALL_KEY;
  const k = key ? `k=${encodeURIComponent(key)}` : "";
  const exit = places.find((p) => p.kind === "exit");
  const root = `${base}/api/journeys/${j.id}`;
  return {
    done: `${root}/done${k ? `?${k}` : ""}`,
    exit: exit ? `${root}/next?to=${exit.id}${k ? `&${k}` : ""}` : undefined,
    callIn: j.forPlaceId ? `${root}/next?to=${j.forPlaceId}${k ? `&${k}` : ""}` : undefined,
  };
}

// minutes for "Expected in": today's average walk to that place, else a range
async function expectedIn(placeId: string) {
  const row = (await getStore().getStats(today())).find((r) => r.placeId === placeId);
  if (!row || !row.walks) return "2-5 min";
  return `about ${Math.max(1, Math.ceil(row.walkSeconds / row.walks / 60))} min`;
}

// message when a leg starts: "A17 is on the way" to the destination, on all of its channels
// (lib/channels.ts). a walk to a waiting room gets its message ("Your visitor has arrived") when the patient sits there
async function notifyLeg(j: Journey, leg: Leg, base: string) {
  const places = await activePlaces();
  const to = places.find((p) => p.id === leg.toPlaceId);
  if (!to) return;
  if (j.forPlaceId && j.forPlaceId !== to.id) return;
  if (to.kind === "exit") {
    await addEvents(j.id, [event("teams_skipped", "Guiding to the exit, no message")]);
    return;
  }
  const message = onTheWayMessage(j, to, await expectedIn(to.id), links(base, j, places));
  await addEvents(j.id, await notifyPlace(to, message));
}

async function notifyVisitor(j: Journey, leg: Leg, base: string) {
  const places = await activePlaces();
  const room = places.find((p) => p.id === j.forPlaceId);
  const waiting = places.find((p) => p.id === leg.toPlaceId);
  if (!room || !waiting) return;
  const message = visitorMessage(j, room, waiting, links(base, j, places));
  await addEvents(j.id, await notifyPlace(room, message));
}

// ---------- create

// source integration = the clinic's own system with INTEGRATION_KEY (counts as staff)
export async function createJourney(
  body: Record<string, unknown>,
  who: Caller,
  base: string,
  source: "board" | "integration" = "board",
) {
  await retention();
  const extra = Object.keys(body).filter((k) => !JOURNEY_FIELDS.includes(k));
  if (extra.length) {
    throw new ApiError(400, `unknown field ${extra[0]}: Otto keeps no patient data, send only ${JOURNEY_FIELDS.join(", ")}`);
  }
  const places = await activePlaces();
  const to = mustPlace(places, body.to, "to");
  const forPlace =
    body.forPlaceId === undefined || body.forPlaceId === null ? undefined : mustPlace(places, body.forPlaceId, "forPlaceId");
  if (forPlace) {
    if (!ROOM_KINDS.includes(forPlace.kind)) throw new ApiError(400, "forPlaceId must be a room");
    if (to.id !== forPlace.id && to.id !== forPlace.waitingPlaceId) {
      throw new ApiError(400, "to must be the room itself or its waiting room");
    }
  }
  if (body.pace !== undefined && !isOneOf(PACES, body.pace)) throw new ApiError(400, `pace must be one of ${PACES.join(", ")}`);
  if (body.wheelchair !== undefined && typeof body.wheelchair !== "boolean")
    throw new ApiError(400, "wheelchair must be true or false");

  // otto never decides medically: on its own it only goes to public places and self check-in rooms
  if (who === "robot" && (forPlace ? !forPlace.selfCheckIn : !to.public)) {
    throw new ApiError(403, "Otto can only take patients to public places or rooms open for self check-in");
  }
  const reception = places.find((p) => p.kind === "reception");
  if (!reception) throw new ApiError(409, "add the reception first (a place of kind reception), Otto picks patients up there");

  const store = getStore();
  const at = now();
  const day = today();
  const pace = isOneOf(PACES, body.pace) ? body.pace : "normal";
  const wheelchair = body.wheelchair === true;
  const robot = robotName(body.robot);
  // only in normal mode (otto's own check-in in demo mode too), and one floor per walk: otto can't take the
  // elevator (robot-setup.md section 2)
  await assertCanGuide(robot, who);
  await checkSameFloor(robot, reception, to);
  if (forPlace && forPlace.id !== to.id) await checkSameFloor(robot, to, forPlace);
  const forPlaceId = forPlace && forPlace.id !== to.id ? forPlace.id : undefined;
  const leg = newLegTo(to, { id: reception.id, name: reception.name, spot: reception.spot }, at);
  const how =
    who === "robot" ? `Checked in on ${robotLabel(robot)}` : source === "integration" ? "Sent by the clinic system" : "Sent by reception";
  const walkCode = newWalkCode();
  const target = forPlace ? placeLabel(forPlace) : placeLabel(to);

  let created: StoredJourney | null = null;
  for (let attempt = 0; attempt < 6 && !created; attempt++) {
    const ticket = pickTicket(await store.usedTickets(day));
    if (!ticket) throw new ApiError(503, "no free tickets left today");
    created = await store.createJourney(
      {
        id: crypto.randomUUID(),
        ticket,
        ticketDay: day,
        robot,
        pace,
        wheelchair,
        status: "waiting_pickup",
        ...(forPlaceId ? { forPlaceId } : {}),
        createdBy: who,
        createdAt: at,
        updatedAt: at,
        walkCode,
      },
      leg,
      [event("created", `${how}: ticket ${ticket} for ${target}`, at)],
    );
  }
  if (!created) throw new ApiError(503, "could not get a free ticket, please try again");

  const command = await queueGuide(created, leg);
  await bump(robot, forPlaceId ?? to.id, { journeys: 1 });
  await bump(robot, to.id, { guides: 1 });
  await notifyLeg(created, leg, base);
  return { journey: await getJourney(created.id), command: withWalkUrl(command, base) };
}

// ---------- robot and staff updates

// demo mode (docs/demo-mode.md): otto can't cancel a walk, so on stop / restart it fails the open leg with this
const DEMO_ENDED = /^the demo was ended by the team/i;

export async function patchJourney(id: string, body: Record<string, unknown>, who: Caller, base: string) {
  await retention();
  const legId = text(body.legId, 80);
  const status = body.status;
  const said = body.event && typeof body.event === "object" ? text((body.event as Record<string, unknown>).detail, 200) : undefined;
  if (who === "robot" && status === "failed" && said && DEMO_ENDED.test(said)) return endDemoJourney(id, said);
  if (status !== undefined && status !== "with_staff" && !isOneOf(ROBOT_LEG_STATUSES, status)) {
    throw new ApiError(400, `status must be one of ${[...ROBOT_LEG_STATUSES, "with_staff"].join(", ")}`);
  }
  if (status === "with_staff" && who === "robot") throw new ApiError(403, "only staff can set with_staff");

  let extra: JourneyEvent | undefined;
  if (body.event !== undefined) {
    const e = body.event as Record<string, unknown> | null;
    if (!e || typeof e !== "object" || typeof e.type !== "string" || !EVENT_TYPE.test(e.type)) {
      throw new ApiError(400, "event needs a type like check_back");
    }
    const detail = text(e.detail, 200);
    if (e.type === "feedback" && detail !== "up" && detail !== "down")
      throw new ApiError(400, "feedback detail must be up or down");
    extra = event(e.type, detail);
  }
  if (status === undefined && !extra) throw new ApiError(400, "send status or event");

  const at = now();
  const { journey, before, changed } = await mutate(id, (j) => {
    if (isClosed(j.status)) throw new ApiError(409, `ticket ${j.ticket} is already ${j.status}`);
    const cur = currentLeg(j);
    const leg = legId ? j.legs.find((l) => l.id === legId) : cur;
    if (!leg) throw new ApiError(404, "leg not found");
    const events: JourneyEvent[] = [];
    const change: JourneyChange = { updatedAt: at, events };

    if (isOneOf(ROBOT_LEG_STATUSES, status)) {
      if (leg.id !== cur.id) throw new ApiError(409, "that leg is over, the journey has a newer one");
      if (FINAL_LEG_STATUSES.includes(leg.status) && leg.status !== status) {
        throw new ApiError(409, `this leg is already ${leg.status}`);
      }
      if (j.status === "with_staff") throw new ApiError(409, `ticket ${j.ticket} is already with staff`);
      if (leg.status !== status) {
        change.leg = {
          id: leg.id,
          status,
          ...(status === "walking" ? { startedAt: at } : {}),
          ...(status === "arrived" ? { arrivedAt: at } : {}),
        };
        change.status = statusForLeg(status);
        if (extra?.type !== legEventType(status)) events.push(event(legEventType(status), legDetail(status, leg), at));
      }
    }
    if (status === "with_staff" && j.status !== "with_staff") {
      change.status = "with_staff";
      if (!FINAL_LEG_STATUSES.includes(leg.status)) change.leg = { id: cur.id, status: "arrived", arrivedAt: at };
      events.push(event("with_staff", `With staff at ${placeLabel({ name: cur.toName, room: cur.toRoom })}`, at));
    }
    if (extra) events.push(extra);
    return events.length || change.leg || change.status ? change : null;
  });

  if (changed) {
    const prev = currentLeg(before);
    const leg = journey.legs.find((l) => l.id === prev.id) ?? currentLeg(journey);
    // a real walk: count it and tell the room. arrival at a waiting room = the visitor card
    if (status === "arrived" && prev.status !== "arrived") {
      const seconds = leg.startedAt && leg.arrivedAt ? (Date.parse(leg.arrivedAt) - Date.parse(leg.startedAt)) / 1000 : null;
      if (seconds !== null) await bump(journey.robot, leg.toPlaceId, { walks: 1, walkSeconds: Math.round(seconds) });
      if (journey.forPlaceId && journey.forPlaceId !== leg.toPlaceId) await notifyVisitor(journey, leg, base);
    }
    if (status === "with_staff" && !FINAL_LEG_STATUSES.includes(prev.status)) {
      await getStore().closeJourneyCommands(id, at, true);
    }
    if (extra?.type === "lost_patient") await bump(journey.robot, leg.toPlaceId, { lostPatients: 1 });
    if (extra?.type === "feedback") await bump(journey.robot, leg.toPlaceId, extra.detail === "up" ? { feedbackUp: 1 } : { feedbackDown: 1 });
  }
  return getJourney(id);
}

function legEventType(status: LegStatus) {
  return status === "pickup" ? "pickup_started" : status;
}

function legDetail(status: LegStatus, leg: Leg) {
  const to = placeLabel({ name: leg.toName, room: leg.toRoom });
  switch (status) {
    case "pickup":
      return `Otto is fetching the patient at ${leg.fromName ?? "the pickup point"}`;
    case "walking":
      return `Walking to ${to}`;
    case "arrived":
      return `Arrived at ${to}`;
    case "failed":
      return "Otto could not finish this walk";
    default:
      return undefined;
  }
}

// staff: next room or the exit, from where the patient is now
export async function nextLeg(id: string, toId: unknown, base: string, via: "board" | "teams" = "board") {
  await retention();
  const places = await activePlaces();
  const to = mustPlace(places, toId, "to");
  const before = await mustGet(id);
  if (!isClosed(before.status)) {
    await assertCanGuide(before.robot);
    const here = (await getStore().listPlaces()).find((p) => p.id === currentLeg(before).toPlaceId);
    await checkSameFloor(before.robot, here, to);
  }
  const at = now();
  let leg: Leg | undefined;
  const { journey } = await mutate(id, (j) => {
    if (isClosed(j.status)) throw new ApiError(409, `ticket ${j.ticket} is already ${j.status}`);
    if (j.status !== "arrived" && j.status !== "with_staff") {
      throw new ApiError(409, `ticket ${j.ticket} is not at a room yet`);
    }
    const cur = currentLeg(j);
    if (cur.toPlaceId === to.id) throw new ApiError(409, `ticket ${j.ticket} is already at ${to.name}`);
    leg = newLegTo(to, { id: cur.toPlaceId, name: cur.toName, spot: cur.toSpot }, at);
    const change: JourneyChange = {
      updatedAt: at,
      status: "waiting_pickup",
      newLeg: leg,
      events: [event("next_leg", `Next: ${placeLabel(to)}${via === "teams" ? " (from Teams)" : ""}`, at)],
    };
    // left the room they came for (or called in from its waiting room): the line follows the new place
    if (j.forPlaceId && j.forPlaceId !== to.id) change.forPlaceId = null;
    return change;
  });
  if (!leg) throw new ApiError(500, "no leg");
  const command = await queueGuide(journey, leg);
  await bump(journey.robot, to.id, { guides: 1 });
  await notifyLeg(journey, leg, base);
  return { journey: await getJourney(id), command: withWalkUrl(command, base) };
}

// staff: otto gave up on a leg (nobody came, lost the patient). try the same walk again
export async function retryLeg(id: string, base: string) {
  await retention();
  const first = await mustGet(id);
  if (!isClosed(first.status)) await assertCanGuide(first.robot);
  const at = now();
  let leg: Leg | undefined;
  const { journey } = await mutate(id, (j) => {
    if (isClosed(j.status)) throw new ApiError(409, `ticket ${j.ticket} is already ${j.status}`);
    const cur = currentLeg(j);
    if (cur.status !== "failed") throw new ApiError(409, `ticket ${j.ticket} does not need a new try`);
    const again: Leg = { ...cur, id: crypto.randomUUID(), status: "queued", createdAt: at };
    delete again.startedAt;
    delete again.arrivedAt;
    leg = again;
    return {
      updatedAt: at,
      status: "waiting_pickup",
      newLeg: leg,
      events: [event("next_leg", `Sent Otto again to ${placeLabel({ name: cur.toName, room: cur.toRoom })}`, at)],
    };
  });
  if (!leg) throw new ApiError(500, "no leg");
  const command = await queueGuide(journey, leg);
  await bump(journey.robot, leg.toPlaceId, { guides: 1 });
  return { journey: await getJourney(id), command: withWalkUrl(command, base) };
}

export async function finishJourney(id: string, via: "board" | "teams" = "board") {
  await retention();
  const at = now();
  const { journey, changed } = await mutate(id, (j) => {
    if (j.status === "done") return null;
    if (j.status === "cancelled") throw new ApiError(409, `ticket ${j.ticket} was cancelled`);
    const cur = currentLeg(j);
    return {
      updatedAt: at,
      status: "done",
      endedAt: at,
      events: [
        event(
          "done",
          `Done at ${placeLabel({ name: cur.toName, room: cur.toRoom })}${via === "teams" ? " (from Teams)" : ""}`,
          at,
        ),
      ],
    };
  });
  if (changed) {
    await getStore().closeJourneyCommands(id, at, true);
    await bump(journey.robot, currentLeg(journey).toPlaceId, { done: 1 });
    await clearJourneyFeed(id);
  }
  return { journey: await getJourney(id), already: !changed };
}

export async function cancelJourney(id: string) {
  await retention();
  const at = now();
  const { journey, changed } = await mutate(id, (j) => {
    if (j.status === "cancelled") return null;
    if (j.status === "done") throw new ApiError(409, `ticket ${j.ticket} is already done`);
    return { updatedAt: at, status: "cancelled", endedAt: at, events: [event("cancelled", "Cancelled by staff", at)] };
  });
  if (changed) {
    await getStore().closeJourneyCommands(id, at, false);
    await bump(journey.robot, lineKey(journey), { cancelled: 1 });
    await clearJourneyFeed(id);
  }
  return getJourney(id);
}

// a demo walk the team ended (stop / restart on the demo page): closed calmly like a cancel, no "needs you",
// not counted as cancelled
export async function endDemoJourney(id: string, detail = "The demo was ended by the team") {
  const at = now();
  const { changed } = await mutate(id, (j) => {
    if (isClosed(j.status)) return null;
    return { updatedAt: at, status: "cancelled", endedAt: at, events: [event("demo_ended", detail, at)] };
  });
  if (changed) {
    await getStore().closeJourneyCommands(id, at, false);
    await clearJourneyFeed(id);
  }
  return getJourney(id);
}

// the robot fetched a guide: one line on the timeline
export async function markAssigned(command: GuideCommand) {
  await addEvents(command.journeyId, [
    event(
      "robot_assigned",
      `${robotLabel(command.robot)} got the guide to ${placeLabel({ name: command.to.name, room: command.to.room })}`,
    ),
  ]).catch(() => undefined);
}
