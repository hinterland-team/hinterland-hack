// the patient's phone page (/walk/<code>) and walk_control commands.
// the page only ever shows the ticket, the destination and how far it is. no other journey, no history
import { event, now, retention } from "./common";
import { ApiError, text } from "./http";
import { addJourneyEvents } from "./journeys";
import { feedId, saveFeed } from "./live-feed";
import { readMap } from "./map";
import { getRobotStatus, showPatientText } from "./robot";
import { getStore } from "./store";
import type { StoredJourney } from "./store-types";
import {
  type AskCommand,
  type FeedItem,
  type Pace,
  WALK_ACTIONS,
  type WalkAction,
  type WalkControlCommand,
  currentLeg,
  isClosed,
  isOneOf,
  normalizeWalkCode,
} from "./types";

// a map frame older than this is not used for "metres left"
export const WALK_FRESH_SECONDS = 30;
export const WALK_TEXT_MAX = 300;

export type WalkPhase = "coming" | "pickup" | "walking" | "arrived" | "with_staff" | "help";

export type WalkView = {
  ticket: string;
  to: { name: string; room?: string };
  phase: WalkPhase;
  pace: Pace;
  wheelchair: boolean;
  // nova is with this ticket right now (pickup or walking): the buttons work
  canControl: boolean;
  remainingMeters: number | null;
  progress: number | null;
};

const ACTION_TEXT: Record<WalkAction, string> = {
  wait: "asked Nova to wait",
  resume: "asked Nova to go on",
  slower: "asked Nova to walk slower",
  faster: "asked Nova to walk faster",
};

export async function journeyForCode(raw: string) {
  const code = normalizeWalkCode(raw);
  const j = code ? await getStore().findJourneyByWalkCode(code) : null;
  if (!j) throw new ApiError(404, "This link is not active");
  if (isClosed(j.status)) throw new ApiError(410, "This walk has ended");
  return j;
}

function phaseOf(j: StoredJourney): WalkPhase {
  const leg = currentLeg(j);
  if (leg.status === "failed") return "help";
  if (j.status === "with_staff") return "with_staff";
  if (j.status === "arrived") return "arrived";
  if (leg.status === "walking") return "walking";
  if (leg.status === "pickup") return "pickup";
  return "coming";
}

function withNova(j: StoredJourney) {
  const s = currentLeg(j).status;
  return s === "pickup" || s === "walking";
}

export async function walkView(j: StoredJourney): Promise<WalkView> {
  const leg = currentLeg(j);
  const phase = phaseOf(j);
  let remainingMeters: number | null = null;
  let progress: number | null = null;
  if (phase === "walking") {
    const [status, map] = await Promise.all([getRobotStatus(j.robot), readMap(j.robot)]);
    // only when nova's own screen says it is walking this ticket
    const onIt = status.online && status.screen?.ticket === j.ticket;
    if (onIt && status.screen?.progress !== undefined) progress = status.screen.progress;
    const fresh = map !== null && Date.now() - Date.parse(map.updatedAt) < WALK_FRESH_SECONDS * 1000;
    if (onIt && fresh && map?.remainingMeters !== undefined) remainingMeters = map.remainingMeters;
  }
  return {
    ticket: j.ticket,
    to: { name: leg.toName, ...(leg.toRoom ? { room: leg.toRoom } : {}) },
    phase,
    pace: j.pace,
    wheelchair: j.wheelchair,
    canControl: withNova(j),
    remainingMeters,
    progress,
  };
}

function notWithNova(j: StoredJourney) {
  const phase = phaseOf(j);
  if (phase === "coming") return new ApiError(409, "Nova is still on its way to you");
  if (phase === "help") return new ApiError(409, "Nova has stopped. A person from the team is coming");
  return new ApiError(409, "Nova is not walking with this ticket right now. Please ask the team");
}

// wait / resume / slower / faster. only while nova is with this ticket, so a tap can't steer another walk
export async function controlWalk(j: StoredJourney, action: unknown, origin: "staff" | "phone") {
  if (!isOneOf(WALK_ACTIONS, action)) throw new ApiError(400, `action must be one of ${WALK_ACTIONS.join(", ")}`);
  if (!withNova(j)) throw notWithNova(j);
  await retention();
  const at = now();
  const command: WalkControlCommand = {
    id: crypto.randomUUID(),
    robot: j.robot,
    type: "walk_control",
    journeyId: j.id,
    action,
    origin,
    createdAt: at,
  };
  await getStore().createCommand(command);
  const who = origin === "phone" ? "Patient" : "Staff";
  await addJourneyEvents(j.id, [event("walk_control", `${who} ${ACTION_TEXT[action]}${origin === "phone" ? " on the phone" : ""}`, at)]);
  if (origin === "phone") {
    await saveFeed(j.robot, [
      { id: feedId(), robot: j.robot, journeyId: j.id, at, who: "patient", kind: "intent", text: `${ACTION_TEXT[action]} (phone)` },
    ]);
  }
  return command;
}

// dashboard: { type: "walk_control", journeyId, action }
export async function staffWalkControl(body: Record<string, unknown>) {
  const id = text(body.journeyId, 80);
  if (!id) throw new ApiError(400, "journeyId is required");
  const j = await getStore().getJourney(id);
  if (!j) throw new ApiError(404, "journey not found");
  if (isClosed(j.status)) throw new ApiError(409, `ticket ${j.ticket} is already ${j.status}`);
  return controlWalk(j, body.action, "staff");
}

// "Talk" on the phone: the words go to nova as an ask command. the db loses the text once nova has it,
// the dashboard shows only "asked something" unless the demo switch is on (then only in memory)
export async function askFromPhone(j: StoredJourney, raw: unknown) {
  const t = text(raw, WALK_TEXT_MAX);
  if (!t) throw new ApiError(400, "Say or type something first");
  if (!withNova(j)) throw notWithNova(j);
  await retention();
  const at = now();
  const command: AskCommand = {
    id: crypto.randomUUID(),
    robot: j.robot,
    type: "ask",
    text: t,
    journeyId: j.id,
    origin: "phone",
    createdAt: at,
  };
  await getStore().createCommand(command);
  const show = await showPatientText();
  const item: FeedItem = show
    ? { id: feedId(), robot: j.robot, journeyId: j.id, at, who: "patient", kind: "said", text: t }
    : { id: feedId(), robot: j.robot, journeyId: j.id, at, who: "patient", kind: "intent", text: "asked Nova something on the phone" };
  await saveFeed(j.robot, [item]);
  return { ok: true };
}
