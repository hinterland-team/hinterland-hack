// virtual otto (/simulate): a simulated robot with its own id (otto-sim) that walks on the live map between the
// real places, does what the robot app does (pickup, walk with check-backs, arrival) and plays the whole demo.
// it only ever takes commands for otto-sim and only changes journeys of otto-sim, never the real robot's.
// runs in the server process (state on globalThis, one loop), so it keeps going when the page is in the background
import { event, now } from "./common";
import { robotConfig } from "./config";
import {
  type DemoPace,
  type DemoPart,
  type DemoScript,
  type DemoState,
  type DemoWaiting,
  PART_INFO,
  WAITING_TEXT,
  checkScript,
  defaultScript,
  fillLine,
  patientWaitS,
  resolvePlace,
  speakingS,
  upcomingPatientLine,
} from "./demo-script";
import { ApiError } from "./http";
import { addJourneyEvents, cancelJourney, createJourney, finishJourney, getJourney, nextLeg, patchJourney } from "./journeys";
import { feedId, saveFeed } from "./live-feed";
import { finishCommand, nextCommand, saveHeartbeat } from "./robot";
import { REAL_ROBOT, SIM_ROBOT } from "./robots";
import { mapImagePng } from "./floors";
import { type SimFloor, type SimSpot, along, pathLength, simFloor, spotOf } from "./sim-floor";
import { type Grid, gridFromPng, planPath } from "./sim-path";
import { getStore } from "./store";
import {
  type DemoCommand,
  type FeedItem,
  type GuideCommand,
  MAP_TRAIL_MAX,
  type Pace,
  type Place,
  ROOM_KINDS,
  type RobotCommand,
  type RobotScreen,
  currentLeg,
  isClosed,
} from "./types";

export const PATIENT_ACTIONS = ["fine", "behind", "blocked", "slower"] as const;
export type PatientAction = (typeof PATIENT_ACTIONS)[number];

const PACE_SPEED: Record<Pace, number> = { normal: 0.6, slow: 0.4, very_slow: 0.25 };
const SLOWER: Record<Pace, Pace> = { normal: "slow", slow: "very_slow", very_slow: "very_slow" };

type LogLine = { at: string; text: string };
type DemoStep = { key: string; title: string; state: "todo" | "now" | "done" | "skipped"; note?: string };

type Walk = {
  journeyId: string;
  ticket: string;
  to: string;
  pace: Pace;
  progress: number;
  patientGap: number;
  phase: "to_pickup" | "pickup" | "walking" | "arrived";
  side?: "left" | "right";
};

type State = {
  on: boolean;
  base: string;
  speed: number;
  floor: SimFloor | null;
  pose: { x: number; y: number; theta: number };
  at: string;
  walk: Walk | null;
  busy: string | null;
  queue: PatientAction[];
  held: RobotCommand[];
  demo: { steps: DemoStep[]; journeyId?: string; ticket?: string; error?: string; running: boolean; finishedAt?: string } | null;
  // demo mode (docs/demo-mode.md), started with the demo command
  demoRun: DemoRun | null;
  log: LogLine[];
  abort: AbortController;
  loop: boolean;
  lastBeat: number;
  lastFrame: number;
  lastPlaces: number;
  lastPoll: number;
  screen: RobotScreen;
  // the corridors of the real floor plan, made once per picture (null: no plan, straight lines)
  grid: { key: string; grid: Grid | null } | null;
};

const g = globalThis as unknown as { __ottoSim?: State };

function speedFactor() {
  const n = Number(process.env.SIM_SPEED);
  return Number.isFinite(n) && n >= 0.5 && n <= 50 ? n : 2.5;
}

function state(): State {
  return (g.__ottoSim ??= {
    on: false,
    base: "",
    speed: speedFactor(),
    floor: null,
    pose: { x: 0, y: 0, theta: 0 },
    at: "",
    walk: null,
    busy: null,
    queue: [],
    held: [],
    demo: null,
    demoRun: null,
    log: [],
    abort: new AbortController(),
    loop: false,
    lastBeat: 0,
    lastFrame: 0,
    lastPlaces: 0,
    lastPoll: 0,
    screen: { view: "idle", title: "Hello, I'm Otto", step: "Simulation" },
    grid: null,
  });
}

class Stopped extends Error {}

function note(text: string) {
  const s = state();
  s.log.push({ at: now(), text });
  if (s.log.length > 60) s.log.splice(0, s.log.length - 60);
}

// real milliseconds, ends early (Stopped) when the simulation is switched off
function pause(ms: number) {
  const signal = state().abort.signal;
  if (signal.aborted) return Promise.reject(new Stopped());
  return new Promise<void>((resolve, reject) => {
    const t = setTimeout(resolve, Math.max(30, ms));
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(t);
        reject(new Stopped());
      },
      { once: true },
    );
  });
}

// story time: shorter when the simulation runs faster (SIM_SPEED)
function sleep(ms: number) {
  return pause(ms / state().speed);
}

// ---------- what the robot app would send

async function beat(extra: { state?: string; goal?: string | null; screen?: RobotScreen } = {}) {
  const s = state();
  if (extra.screen) s.screen = extra.screen;
  s.lastBeat = Date.now();
  const d = s.demoRun;
  await saveHeartbeat({
    // demo mode: the mode and where virtual otto is in the script, like the robot app's heartbeat
    ...(d ? { mode: d.on ? "demo" : "normal", demo: demoState(d) } : {}),
    robot: SIM_ROBOT,
    battery: 88,
    charging: false,
    state: extra.state ?? (s.walk ? "walking" : "idle"),
    position: s.at || null,
    goal: extra.goal === undefined ? (s.walk ? `Guiding ${s.walk.ticket} to ${s.walk.to}` : null) : extra.goal,
    screen: s.screen,
    version: "simulation",
    mapName: s.floor?.mapName ?? null,
    localised: true,
  });
}

async function frame(force = false) {
  const s = state();
  if (!s.floor) return;
  const t = Date.now();
  if (!force && t - s.lastFrame < 400) return;
  s.lastFrame = t;
  const sendPlaces = force || t - s.lastPlaces > 30_000;
  if (sendPlaces) s.lastPlaces = t;
  const w = s.walk;
  const target = w ? spotOf(s.floor, w.phase === "to_pickup" ? undefined : w.to) : undefined;
  let follower: { x: number; y: number; source: "sim" } | undefined;
  if (w && (w.phase === "walking" || w.phase === "pickup")) {
    // the simulated patient: in front at the pickup, else a little behind on otto's left
    const back = w.phase === "pickup" ? -1.2 : w.patientGap;
    const side = w.phase === "pickup" ? 0 : w.side === "right" ? -0.6 : 0.6;
    follower = {
      x: Math.round((s.pose.x - Math.cos(s.pose.theta) * back - Math.sin(s.pose.theta) * side) * 100) / 100,
      y: Math.round((s.pose.y - Math.sin(s.pose.theta) * back + Math.cos(s.pose.theta) * side) * 100) / 100,
      source: "sim",
    };
  }
  await getStore().saveMap(
    {
      robot: SIM_ROBOT,
      mapName: s.floor.mapName,
      pose: { x: Math.round(s.pose.x * 100) / 100, y: Math.round(s.pose.y * 100) / 100, theta: Math.round(s.pose.theta * 1000) / 1000 },
      ...(sendPlaces ? { places: s.floor.spots.map(({ name, x, y }) => ({ name, x, y })) } : {}),
      scan: [],
      ...(follower ? { follower } : {}),
      ...(target ? { target: { name: target.name, x: target.x, y: target.y } } : {}),
      ...(w && target ? { remainingMeters: Math.round(Math.hypot(target.x - s.pose.x, target.y - s.pose.y) * 10) / 10 } : {}),
      at: now(),
    },
    MAP_TRAIL_MAX,
  );
}

async function say(items: Omit<FeedItem, "id" | "robot" | "at">[], journeyId?: string) {
  const at = now();
  await saveFeed(
    SIM_ROBOT,
    items.map((i) => ({ id: feedId(), robot: SIM_ROBOT, at, ...(journeyId ? { journeyId } : {}), ...i })),
  );
}

// only journeys of virtual otto: the real robot's are never touched
async function mine(journeyId: string) {
  const j = await getJourney(journeyId);
  if (j.robot !== SIM_ROBOT) throw new ApiError(409, "that walk belongs to the real Otto, the simulation leaves it alone");
  return j;
}

async function patch(journeyId: string, legId: string, body: { status?: string; event?: { type: string; detail?: string } }) {
  await mine(journeyId);
  return patchJourney(journeyId, { legId, ...body }, "robot", state().base);
}

// ---------- moving

async function refreshFloor() {
  const s = state();
  const store = getStore();
  const [places, real] = await Promise.all([store.listPlaces(), store.getMap(REAL_ROBOT)]);
  s.floor = simFloor(places, real);
  // the real floor plan, when otto sent one: the walks follow its corridors
  const plan = s.floor.real ? await mapImagePng(SIM_ROBOT, s.floor.mapName).catch(() => null) : null;
  const key = plan ? `${s.floor.mapName}|${plan.meta.hash}|${plan.meta.originX}|${plan.meta.originY}|${plan.meta.resolution}` : "";
  if (!plan) s.grid = null;
  else if (s.grid?.key !== key) s.grid = { key, grid: gridFromPng(new Uint8Array(plan.png), plan.meta) };
  return places;
}

// the way to a spot: along the corridors when there is a floor plan, else a straight line
function wayTo(from: { x: number; y: number }, to: { x: number; y: number }) {
  const g = state().grid?.grid;
  return (g && planPath(g, from, to)) || [from, to];
}

async function driveTo(spot: SimSpot, opts: { patient?: boolean } = {}) {
  const s = state();
  let points = wayTo({ x: s.pose.x, y: s.pose.y }, { x: spot.x, y: spot.y });
  let total = pathLength(points);
  let done = 0;
  let nextCheck = 0.4;
  const stepMs = 250;
  while (done < total) {
    const w = s.walk;
    // patient actions from the buttons on /simulate
    if (opts.patient && w && s.queue.length) {
      const action = s.queue.shift() as PatientAction;
      const detour = await react(action, w);
      if (detour && !s.grid?.grid) {
        // a waypoint beside the blocked part: the rest of the way goes round it (on the floor plan the corridor decides)
        const here = along(points, done);
        const nx = -Math.sin(here.theta) * 1.6;
        const ny = Math.cos(here.theta) * 1.6;
        const ahead = along(points, Math.min(total, done + 2.2));
        points = [{ x: here.x, y: here.y }, { x: (here.x + ahead.x) / 2 + nx, y: (here.y + ahead.y) / 2 + ny }, { x: spot.x, y: spot.y }];
        total = pathLength(points);
        done = 0;
      }
      continue;
    }
    if (opts.patient && w && total > 4 && done / total >= nextCheck) {
      nextCheck += 0.35;
      await react("fine", w, true);
    }
    await commandsWhileBusy();
    const speed = (w ? PACE_SPEED[w.pace] : 0.7) * s.speed;
    done = Math.min(total, done + (speed * stepMs) / 1000);
    const p = along(points, done);
    s.pose = { x: p.x, y: p.y, theta: p.theta };
    if (w) {
      w.progress = total ? done / total : 1;
      if (w.phase === "walking") s.screen = { ...s.screen, view: "walking", progress: Math.round(Math.max(0.05, w.progress) * 100) / 100 };
    }
    await frame();
    if (Date.now() - s.lastBeat > 2000) await beat();
    await pause(stepMs);
  }
  s.at = spot.name;
  await frame(true);
}

// a check-back or a patient action during the walk. returns true when the way needs a detour
async function react(action: PatientAction, w: Walk, auto = false): Promise<boolean> {
  const legId = await currentLegId(w.journeyId);
  if (!legId) return false;
  const screen = (view: RobotScreen["view"], title: string, step: string): RobotScreen => ({
    view,
    ticket: w.ticket,
    title,
    step,
    progress: Math.round(Math.max(0.05, w.progress) * 100) / 100,
  });
  if (action === "fine") {
    note(auto ? `${w.ticket}: check-back, the patient is close` : `${w.ticket}: the patient walks fine`);
    await beat({ screen: screen("check_back", "All good?", "We're almost there") });
    await say([{ who: "nova", kind: "said", text: "All good? We're almost there." }, { who: "patient", kind: "intent", text: "said all is fine" }], w.journeyId);
    await patch(w.journeyId, legId, { event: { type: "check_back", detail: "Patient close, all fine" } });
    await sleep(1400);
    await beat({ screen: screen("walking", `Walking to ${w.to}`, w.progress > 0.6 ? "Almost there" : "Half way") });
    return false;
  }
  if (action === "behind") {
    note(`${w.ticket}: the patient falls behind, Otto waits`);
    for (let gap = w.patientGap; gap < 4; gap += 0.7) {
      w.patientGap = gap;
      await frame(true);
      await sleep(250);
    }
    await beat({ screen: screen("waiting", "I'll wait for you", "Take your time") });
    await say([{ who: "nova", kind: "said", text: "I'll wait for you. Take your time." }], w.journeyId);
    await patch(w.journeyId, legId, { event: { type: "waiting_for_patient", detail: "Patient 4 m behind, Otto waits" } });
    await sleep(2200);
    for (let gap = w.patientGap; gap > 1.2; gap -= 0.7) {
      w.patientGap = gap;
      await frame(true);
      await sleep(250);
    }
    w.patientGap = 1.2;
    w.pace = SLOWER[w.pace];
    await patch(w.journeyId, legId, { event: { type: "slowed_down", detail: "Slowed down one step" } });
    await say([{ who: "nova", kind: "action", text: "Slowed down one step" }], w.journeyId);
    await beat({ screen: screen("walking", `Walking to ${w.to}`, "Nice and easy") });
    return false;
  }
  if (action === "blocked") {
    note(`${w.ticket}: the way is blocked, Otto takes another way`);
    await beat({ screen: screen("walking", `Walking to ${w.to}`, "Excuse me, may I pass?") });
    await say([{ who: "nova", kind: "said", text: "Excuse me, may I pass?" }], w.journeyId);
    await sleep(2400);
    await patch(w.journeyId, legId, { event: { type: "walking", detail: "The way was blocked, Otto takes another way" } });
    await say([{ who: "nova", kind: "action", text: "The way is blocked, taking another way" }], w.journeyId);
    await beat({ screen: screen("walking", `Walking to ${w.to}`, "This way is blocked. Let's take another way") });
    return true;
  }
  note(`${w.ticket}: the patient asks to walk slower`);
  w.pace = SLOWER[w.pace];
  await say([{ who: "patient", kind: "intent", text: "asked to slow down" }, { who: "nova", kind: "said", text: "Of course, I'll walk slower." }], w.journeyId);
  await patch(w.journeyId, legId, { event: { type: "slowed_down", detail: "The patient asked to walk slower" } });
  return false;
}

async function currentLegId(journeyId: string) {
  try {
    const j = await mine(journeyId);
    return isClosed(j.status) ? null : currentLeg(j).id;
  } catch {
    return null;
  }
}

// the robot app polls every 3 s, also while walking: staff "Wait" / "Slower" and "Say" work during the walk
async function commandsWhileBusy() {
  const s = state();
  const t = Date.now();
  if (t - s.lastPoll < 1500) return;
  s.lastPoll = t;
  const c = await nextCommand(SIM_ROBOT);
  if (!c) return;
  if (c.type === "guide") {
    // demo mode: its own walk, or a walk from the board it refuses
    if (s.demoRun?.on) s.demoRun.guides.push(c);
    else s.held.push(c);
    return;
  }
  await quickCommand(c);
}

async function quickCommand(c: RobotCommand) {
  const s = state();
  if (c.type === "demo") {
    await finishCommand(c.id, demoCommand(c)).catch(() => undefined);
    return;
  }
  // demo mode: no ai answers and no floor switch (the robot app refuses them); go_to ends the run, drives there
  // and holds (resume: the next person there, restart: back to the reception)
  if (s.demoRun?.on && (c.type === "ask" || c.type === "switch_map")) {
    await finishCommand(c.id, { ok: false, detail: "Otto is in Demo mode" }).catch(() => undefined);
    return;
  }
  if (s.demoRun?.on && c.type === "go_to") {
    s.demoRun.goTo = c.spot;
    s.demoRun.hold = false;
    await finishCommand(c.id, { ok: true, detail: `The run ends, Virtual Otto drives to ${c.spot} and holds there` }).catch(() => undefined);
    return;
  }
  if (c.type === "say") {
    await say([{ who: "nova", kind: "said", text: c.text }]);
    if (s.demoRun?.on) {
      s.demoRun.line = c.text;
      await dbeat(s.demoRun).catch(() => undefined);
    }
  } else if (c.type === "ask") {
    await say([{ who: "nova", kind: "action", text: "Simulation: the real Otto's AI would answer this" }]);
  } else if (c.type === "walk_control" && s.walk) {
    if (c.action === "slower") s.walk.pace = SLOWER[s.walk.pace];
    if (c.action === "faster") s.walk.pace = s.walk.pace === "very_slow" ? "slow" : "normal";
    if (c.action === "wait") await sleep(3000);
    await say([{ who: "nova", kind: "action", text: { wait: "Waited for the patient", resume: "Walks on", slower: "Walks slower", faster: "Walks faster" }[c.action] }], c.journeyId);
  } else if (c.type === "switch_map") {
    await say([{ who: "nova", kind: "action", text: "Simulation: Virtual Otto stays on this floor" }]);
  }
  await finishCommand(c.id).catch(() => undefined);
}

// ---------- one guide, like the robot app

async function runGuide(c: GuideCommand) {
  const s = state();
  const j = await mine(c.journeyId).catch(() => null);
  if (!j || isClosed(j.status)) {
    await finishCommand(c.id).catch(() => undefined);
    return;
  }
  await refreshFloor();
  const floor = s.floor as SimFloor;
  const from = spotOf(floor, c.from.spot);
  const to = spotOf(floor, c.to.spot);
  if (!from || !to) {
    note(`${c.ticket}: ${!from ? c.from.spot : c.to.spot} is not on the map, the walk fails`);
    await patch(c.journeyId, c.legId, { status: "failed", event: { type: "failed", detail: "The spot is not on Virtual Otto's map" } });
    await finishCommand(c.id).catch(() => undefined);
    return;
  }
  const toText = c.to.room ? `${c.to.name}, Room ${c.to.room}` : c.to.name;
  s.walk = { journeyId: c.journeyId, ticket: c.ticket, to: c.to.name, pace: c.wheelchair && c.pace === "normal" ? "slow" : c.pace, progress: 0, patientGap: 1.2, phase: "to_pickup" };
  note(`${c.ticket}: got the walk to ${toText}`);
  await say([{ who: "nova", kind: "action", text: `Got the guide for ${c.ticket} to ${c.to.name}` }], c.journeyId);
  if (Math.hypot(from.x - s.pose.x, from.y - s.pose.y) > 0.3) {
    await beat({ state: "fetching", goal: `Picking up ${c.ticket} at ${c.from.name}`, screen: { view: "idle", title: "On my way", step: `Picking up ${c.ticket}` } });
    await driveTo(from);
  }
  // pickup
  s.walk.phase = "pickup";
  await patch(c.journeyId, c.legId, { status: "pickup" });
  await beat({ state: "fetching", goal: `Picking up ${c.ticket} at ${c.from.name}`, screen: { view: "pickup", ticket: c.ticket, title: "Please come to me", step: `Going to ${c.to.name}` } });
  await say([{ who: "nova", kind: "said", text: `Ticket ${c.ticket}, please come to Otto.` }], c.journeyId);
  await frame(true);
  await sleep(2600);
  await patch(c.journeyId, c.legId, { event: { type: "patient_confirmed", detail: "Patient tapped That's me" } });
  await say([{ who: "patient", kind: "intent", text: `confirmed ticket ${c.ticket}` }, { who: "nova", kind: "said", text: "Hello! Please walk next to me, on my left." }], c.journeyId);
  // walking
  s.walk.phase = "walking";
  await patch(c.journeyId, c.legId, { status: "walking" });
  await beat({ state: "walking", screen: { view: "walking", ticket: c.ticket, title: `Walking to ${c.to.name}`, step: "Follow me, nice and easy", progress: 0.05 } });
  await say([{ who: "nova", kind: "said", text: `Let's go. This way to ${toText}.` }], c.journeyId);
  await driveTo(to, { patient: true });
  // arrival
  s.walk.phase = "arrived";
  s.walk.progress = 1;
  await patch(c.journeyId, c.legId, { status: "arrived" });
  await beat({ state: "talking", goal: `Arrived with ${c.ticket}`, screen: { view: "arrived", ticket: c.ticket, title: `This is ${toText}`, step: "Please take a seat, the team knows you're here", progress: 1 } });
  await say([{ who: "nova", kind: "said", text: `This is ${toText}. Please take a seat, the team knows you're here.` }], c.journeyId);
  note(`${c.ticket}: arrived at ${toText}`);
  await sleep(2400);
  await finishCommand(c.id).catch(() => undefined);
  s.walk = null;
}

async function goHome() {
  const s = state();
  const places = await refreshFloor();
  const reception = places.find((p) => p.kind === "reception" && p.active);
  const spot = reception && s.floor ? spotOf(s.floor, reception.spot) : undefined;
  if (!spot || Math.hypot(spot.x - s.pose.x, spot.y - s.pose.y) < 0.3) return;
  await beat({ state: "returning", goal: "Back to the reception", screen: { view: "idle", title: "Hello, I'm Otto", step: "Back to the reception" } });
  await driveTo(spot);
}

async function runCommand(c: RobotCommand) {
  const s = state();
  if (c.robot !== SIM_ROBOT) return;
  if (c.type === "guide" && s.demoRun?.on) {
    s.demoRun.guides.push(c);
    return;
  }
  if (c.type === "guide") {
    s.busy = c.ticket;
    try {
      await runGuide(c);
    } finally {
      s.busy = null;
    }
    return;
  }
  if (c.type === "go_to" && !s.demoRun?.on) {
    await refreshFloor();
    const spot = s.floor ? spotOf(s.floor, c.spot) : undefined;
    if (spot) {
      note(`drives to ${c.spot}`);
      await beat({ state: "walking", goal: `Going to ${c.spot}`, screen: { view: "idle", title: "On my way", step: `Going to ${c.spot}` } });
      await driveTo(spot);
      await beat({ state: "idle", goal: null, screen: { view: "idle", title: "Hello, I'm Otto", step: "Simulation" } });
    } else note(`${c.spot} is not on the map`);
    await finishCommand(c.id).catch(() => undefined);
    return;
  }
  await quickCommand(c);
}

async function loop() {
  const s = state();
  if (s.loop) return;
  s.loop = true;
  try {
    await refreshFloor();
    const places = await getStore().listPlaces();
    const reception = places.find((p) => p.kind === "reception" && p.active);
    const start = reception && s.floor ? spotOf(s.floor, reception.spot) : s.floor?.spots[0];
    if (start && !s.at) {
      s.pose = { x: start.x, y: start.y, theta: 0 };
      s.at = start.name;
    }
    await beat({ state: "idle", goal: null, screen: { view: "idle", title: "Hello, I'm Otto", step: "Simulation" } });
    await frame(true);
    while (s.on) {
      const c = s.held.shift() ?? (await nextCommand(SIM_ROBOT));
      if (c) {
        await runCommand(c);
        // back to the reception when nothing else waits (in demo mode the demo drives)
        if (!s.held.length && s.on && !s.demoRun?.on) {
          const next = await nextCommand(SIM_ROBOT);
          if (next) s.held.push(next);
          else await goHome();
        }
        continue;
      }
      if (Date.now() - s.lastBeat > 10_000) await beat();
      if (Date.now() - s.lastFrame > 15_000) await frame(true);
      // like the robot app's poll for jobs
      await pause(1000);
    }
  } catch (err) {
    // switched off in the middle of a step: nothing to report
    if (!(err instanceof Stopped) && s.on) {
      note(`stopped: ${err instanceof Error ? err.message : "error"}`);
      console.error("simulation stopped", err instanceof Error ? err.message : err);
    }
  } finally {
    s.loop = false;
  }
}

// ---------- demo mode (docs/demo-mode.md): the script's lines word for word, timings like the robot app

class DemoRestart extends Error {}
class DemoStop extends Error {}
// go_to in demo mode: the run ends, otto drives there and holds
class DemoGoTo extends Error {}

type DemoRun = {
  script: DemoScript;
  on: boolean;
  part: DemoPart | null;
  step: number | null;
  total: number | null;
  waitingFor: DemoWaiting | null;
  line: string | null;
  nextPatientLine: string | null;
  ticket: string | null;
  runs: number;
  hold: boolean;
  // every Next counts up; a wait ends when it changed since the wait began
  nextSeq: number;
  restart: boolean;
  stop: boolean;
  // walk parts played on this walk (each once)
  played: Set<DemoPart>;
  journeyId: string | null;
  commandId: string | null;
  // guides polled meanwhile: the demo's own walk, or one from the board that is refused
  guides: GuideCommand[];
  pace: Pace;
  linePauseS: number;
  destName: string | null;
  // go_to during the demo: where to drive; after it the next run waits there (resume) until restart
  goTo: string | null;
  stayAt: SimSpot | null;
};

const WALK_PARTS: DemoPart[] = ["firstCheck", "fellBack"];
// virtual otto "sees" the person this long after it stands at the reception (start: person). Next starts at once
const PERSON_SEEN_MS = 4000;

export function simIsOn() {
  return state().on;
}

function demoState(d: DemoRun): DemoState {
  return {
    running: d.on,
    part: d.part,
    step: d.step,
    total: d.total,
    waitingFor: d.waitingFor,
    line: d.line,
    nextPatientLine: d.nextPatientLine,
    ticket: d.ticket,
    runs: d.runs,
  };
}

function walkScreen(d: DemoRun, line: string | null): RobotScreen {
  const w = state().walk;
  return {
    view: "walking",
    ...(d.ticket ? { ticket: d.ticket } : {}),
    title: `Walking to ${d.destName ?? "the room"}`,
    step: line ?? "Please walk on my left",
    progress: Math.round(Math.max(0.05, w?.progress ?? 0) * 100) / 100,
  };
}

async function dbeat(d: DemoRun, screen?: RobotScreen) {
  const moving = d.waitingFor === "walk" || d.waitingFor === "reception" || d.waitingFor === "back";
  await beat({
    state: !d.on ? "idle" : moving ? (d.waitingFor === "walk" ? "walking" : "returning") : d.line && !d.waitingFor ? "talking" : "idle",
    goal: !d.on ? null : d.part ? `Demo: ${PART_INFO[d.part].title}` : d.waitingFor ? `Demo: ${WAITING_TEXT[d.waitingFor]}` : "Demo",
    ...(screen ? { screen } : {}),
  });
}

// the guides polled meanwhile: the demo's own walk is kept, a walk from the board is refused like on the robot
async function settleGuides(d: DemoRun) {
  for (const c of d.guides.splice(0)) {
    if (d.journeyId && c.journeyId === d.journeyId) {
      d.commandId = c.id;
      continue;
    }
    note(`${c.ticket}: refused, Virtual Otto is in demo mode`);
    const legId = await currentLegId(c.journeyId);
    if (legId) await patch(c.journeyId, legId, { status: "failed", event: { type: "failed", detail: "Otto is in Demo mode" } }).catch(() => undefined);
    await finishCommand(c.id, { ok: false, detail: "Otto is in Demo mode" }).catch(() => undefined);
  }
}

async function interrupt(d: DemoRun) {
  if (d.stop) throw new DemoStop();
  if (d.restart) throw new DemoRestart();
  if (d.goTo) throw new DemoGoTo();
  if (d.guides.length) await settleGuides(d);
}

// hold: before the next step, and on the way otto stands still, until resume
async function holdPoint(d: DemoRun) {
  await interrupt(d);
  if (!d.hold) return;
  const before = d.waitingFor;
  d.waitingFor = "hold";
  await dbeat(d);
  while (d.hold) {
    await interrupt(d);
    await pause(150);
  }
  d.waitingFor = before;
  await dbeat(d);
}

// real time (otto's voice, the pause after a line); stop and restart still work
async function demoSleep(d: DemoRun, ms: number) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    await interrupt(d);
    await pause(Math.min(150, end - Date.now()));
  }
}

// a wait that Next ends at once (a person, the patient's line, until the patient is close)
async function demoWait(d: DemoRun, what: DemoWaiting, ms: number, tick?: () => Promise<boolean>) {
  const seq = d.nextSeq;
  d.waitingFor = what;
  await dbeat(d);
  const end = Date.now() + ms;
  while (Date.now() < end && d.nextSeq === seq) {
    await interrupt(d);
    if (tick && (await tick())) break;
    await pause(150);
  }
  d.waitingFor = null;
}

async function ottoLine(d: DemoRun, text: string, waitS?: number) {
  d.line = text;
  d.waitingFor = null;
  const walking = state().walk?.phase === "walking";
  await dbeat(
    d,
    walking ? walkScreen(d, text) : { view: d.ticket ? "checkin" : "idle", ...(d.ticket ? { ticket: d.ticket } : {}), title: text, step: "" },
  );
  await say([{ who: "nova", kind: "said", text }], d.journeyId ?? undefined);
  await demoSleep(d, speakingS(text) * 1000);
  await demoSleep(d, (waitS ?? d.linePauseS) * 1000);
}

async function demoCheckIn(d: DemoRun) {
  const places = (await getStore().listPlaces()).filter((p) => p.active);
  const dest = resolvePlace(places, d.script.destination);
  d.destName = dest?.name ?? d.script.destination;
  try {
    if (!dest) throw new Error(`${d.script.destination} is not on the Places page`);
    const body = ROOM_KINDS.includes(dest.kind) ? { to: dest.id, forPlaceId: dest.id, robot: SIM_ROBOT } : { to: dest.id, robot: SIM_ROBOT };
    const created = await createJourney(body, "robot", state().base);
    d.journeyId = created.journey.id;
    d.ticket = created.journey.ticket;
    d.commandId = created.command.id;
    note(`demo: checked in, ticket ${d.ticket} for ${dest.name}`);
  } catch (err) {
    // like the robot app: the demo goes on with a local ticket
    d.ticket = `L${10 + Math.floor(Math.random() * 90)}`;
    note(`demo: the check-in did not work (${err instanceof Error ? err.message : "error"}), local ticket ${d.ticket}`);
  }
  await say([{ who: "nova", kind: "action", text: `Checked in: ticket ${d.ticket}` }], d.journeyId ?? undefined);
  await dbeat(d, { view: "checkin", ticket: d.ticket ?? undefined, title: `Your ticket: ${d.ticket}`, step: d.destName ?? "" });
}

async function demoPace(d: DemoRun, value: DemoPace) {
  d.pace = value;
  const w = state().walk;
  if (w) w.pace = value;
  const label = value === "very_slow" ? "very slow" : value;
  if (d.journeyId && value !== "normal") {
    const legId = await currentLegId(d.journeyId);
    if (legId) await patch(d.journeyId, legId, { event: { type: "slowed_down", detail: `Pace: ${label}, as the patient asked` } }).catch(() => undefined);
  }
  await say([{ who: "nova", kind: "action", text: `Pace: ${label}` }], d.journeyId ?? undefined);
}

// wait_close: the simulated patient catches up after a few seconds (or Next), at most the script's closeMaxS
async function demoClose(d: DemoRun) {
  const w = state().walk;
  const t0 = Date.now();
  await demoWait(d, "close", d.script.closeMaxS * 1000, async () => {
    if (w) {
      w.patientGap = Math.max(1.2, w.patientGap - 0.12);
      await frame(true);
    }
    return Date.now() - t0 > 2500 && (!w || w.patientGap <= 1.21);
  });
  if (w) w.patientGap = 1.2;
}

function demoAfter(d: DemoRun, part: DemoPart): DemoPart[] {
  if (part === "reception") return [...WALK_PARTS, "arrive"];
  if (part === "arrive") return ["reception"];
  if (part === "obstacle" || part === "detour") return [...WALK_PARTS.filter((p) => !d.played.has(p)), "arrive"];
  return [...WALK_PARTS.filter((p) => p !== part && !d.played.has(p)), "arrive"];
}

async function playPart(d: DemoRun, part: DemoPart) {
  const steps = d.script.parts[part] ?? [];
  if (!steps.length) return;
  const after = demoAfter(d, part);
  d.part = part;
  d.total = steps.length;
  try {
    for (let i = 0; i < steps.length; i++) {
      await holdPoint(d);
      const st = steps[i];
      d.step = i + 1;
      d.nextPatientLine = upcomingPatientLine(d.script, part, i, after);
      if (st.who === "otto") await ottoLine(d, fillLine(st.text, { ticket: d.ticket, place: d.destName }), st.waitS);
      else if (st.who === "patient") await demoWait(d, "patient", patientWaitS(st) * 1000);
      else if (st.do === "check_in") await demoCheckIn(d);
      else if (st.do === "pace") await demoPace(d, st.value);
      else await demoClose(d);
    }
  } finally {
    d.part = null;
    d.step = null;
    d.total = null;
    d.nextPatientLine = upcomingPatientLine(d.script, null, 0, after);
  }
}

async function walkPart(d: DemoRun, part: DemoPart) {
  const w = state().walk;
  d.played.add(part);
  const legId = d.journeyId ? await currentLegId(d.journeyId) : null;
  if (part === "fellBack" && w) {
    // the patient stays back: nobody beside otto any more
    for (let gap = w.patientGap; gap < 4; gap += 0.7) {
      w.patientGap = gap;
      await frame(true);
      await pause(250);
    }
    if (legId && d.journeyId) {
      await patch(d.journeyId, legId, { event: { type: "waiting_for_patient", detail: "Nobody beside Otto, he waits (demo)" } }).catch(() => undefined);
    }
  }
  if (part === "firstCheck" && legId && d.journeyId) {
    await patch(d.journeyId, legId, { event: { type: "check_back", detail: "First check-back (demo)" } }).catch(() => undefined);
  }
  // the first check: otto turns glanceDeg towards the patient's side, plays the part and turns back
  const s = state();
  const glance = part === "firstCheck" ? ((d.script.walkSide === "right" ? -1 : 1) * d.script.glanceDeg * Math.PI) / 180 : 0;
  if (glance) {
    s.pose = { ...s.pose, theta: s.pose.theta + glance };
    await frame(true);
  }
  try {
    await playPart(d, part);
  } finally {
    if (glance) {
      s.pose = { ...s.pose, theta: s.pose.theta - glance };
      await frame(true).catch(() => undefined);
    }
  }
  if (w) w.patientGap = 1.2;
  d.waitingFor = "walk";
  await dbeat(d, walkScreen(d, null));
}

async function demoDrive(d: DemoRun, spot: SimSpot) {
  const s = state();
  const points = wayTo({ x: s.pose.x, y: s.pose.y }, { x: spot.x, y: spot.y });
  const total = pathLength(points);
  let done = 0;
  while (done < total) {
    await holdPoint(d);
    await commandsWhileBusy();
    done = Math.min(total, done + (0.7 * s.speed * 250) / 1000);
    const p = along(points, done);
    s.pose = { x: p.x, y: p.y, theta: p.theta };
    await frame();
    if (Date.now() - s.lastBeat > 2000) await dbeat(d);
    await pause(250);
  }
  s.at = spot.name;
  await frame(true);
}

// 4. the walk to the destination at the chosen pace, with the walk parts as cues
async function demoWalk(d: DemoRun) {
  const s = state();
  await refreshFloor();
  const places = (await getStore().listPlaces()).filter((p) => p.active);
  const dest = resolvePlace(places, d.script.destination);
  const spot = s.floor ? (dest ? spotOf(s.floor, dest.spot) : undefined) ?? spotOf(s.floor, d.script.destination) : undefined;
  if (!spot) {
    note(`demo: ${d.script.destination} is not on Virtual Otto's map, no walk`);
    return;
  }
  d.destName = dest?.name ?? spot.name;
  s.walk = { journeyId: d.journeyId ?? "", ticket: d.ticket ?? "", to: d.destName, pace: d.pace, progress: 0, patientGap: 1.2, phase: "walking", side: d.script.walkSide };
  const legId = d.journeyId ? await currentLegId(d.journeyId) : null;
  if (d.journeyId && legId) {
    await patch(d.journeyId, legId, { status: "pickup" }).catch(() => undefined);
    await patch(d.journeyId, legId, { event: { type: "patient_confirmed", detail: "The patient is with Otto (demo)" } }).catch(() => undefined);
    await patch(d.journeyId, legId, { status: "walking" }).catch(() => undefined);
  }
  d.waitingFor = "walk";
  d.nextPatientLine = upcomingPatientLine(d.script, null, 0, [...WALK_PARTS, "arrive"]);
  await dbeat(d, walkScreen(d, null));
  let points = wayTo({ x: s.pose.x, y: s.pose.y }, { x: spot.x, y: spot.y });
  let total = pathLength(points);
  let done = 0;
  const t0 = Date.now();
  let seq = d.nextSeq;
  // ms of driving (story time, like the walk itself) since the first check part ended
  let driven: number | null = null;
  const w = s.walk;
  const left = () => {
    const here = along(points, done);
    return Math.hypot(spot.x - here.x, spot.y - here.y);
  };
  while (done < total) {
    await holdPoint(d);
    const progress = total ? done / total : 1;
    if (!d.played.has("firstCheck") && (Date.now() - t0 >= 10_000 / s.speed || progress >= 0.35)) {
      await walkPart(d, "firstCheck");
      driven = 0;
      seq = d.nextSeq;
      continue;
    }
    if (driven !== null && !d.played.has("fellBack") && driven >= d.script.fellBackAfterS * 1000) {
      if (left() < 2) {
        // too close to the destination: no waiting any more
        d.played.add("fellBack");
        note("demo: fell back skipped, Otto is within 2 m of the destination");
      } else await walkPart(d, "fellBack");
      seq = d.nextSeq;
      continue;
    }
    // Next with no part running: the next walk part not played yet
    if (d.nextSeq !== seq) {
      seq = d.nextSeq;
      const part = WALK_PARTS.find((p) => !d.played.has(p));
      if (part) await walkPart(d, part);
      seq = d.nextSeq;
      continue;
    }
    // the patient buttons on /simulate
    if (s.queue.length) {
      const action = s.queue.shift() as PatientAction;
      if (action === "behind" && !d.played.has("fellBack")) await walkPart(d, "fellBack");
      else if (action === "blocked") {
        if (d.journeyId && legId) {
          await patch(d.journeyId, legId, { event: { type: "walking", detail: "The way was blocked, Otto takes another way (demo)" } }).catch(() => undefined);
        }
        await playPart(d, "obstacle");
        await playPart(d, "detour");
        d.waitingFor = "walk";
        if (!s.grid?.grid) {
          const here = along(points, done);
          const nx = -Math.sin(here.theta) * 1.6;
          const ny = Math.cos(here.theta) * 1.6;
          const ahead = along(points, Math.min(total, done + 2.2));
          points = [{ x: here.x, y: here.y }, { x: (here.x + ahead.x) / 2 + nx, y: (here.y + ahead.y) / 2 + ny }, { x: spot.x, y: spot.y }];
          total = pathLength(points);
          done = 0;
        }
      } else if (action === "slower") await demoPace(d, SLOWER[d.pace]);
      seq = d.nextSeq;
      continue;
    }
    // later check-backs do nothing in the demo (no stop, no turn)
    await commandsWhileBusy();
    done = Math.min(total, done + (PACE_SPEED[d.pace] * s.speed * 250) / 1000);
    if (driven !== null) driven += 250 * s.speed;
    const p = along(points, done);
    s.pose = { x: p.x, y: p.y, theta: p.theta };
    w.progress = total ? done / total : 1;
    await frame();
    if (Date.now() - s.lastBeat > 2000) await dbeat(d, walkScreen(d, null));
    await pause(250);
  }
  s.at = spot.name;
  w.phase = "arrived";
  w.progress = 1;
  await frame(true);
  if (d.journeyId && legId) await patch(d.journeyId, legId, { status: "arrived" }).catch(() => undefined);
  d.waitingFor = null;
}

async function receptionSpot(d: DemoRun) {
  const s = state();
  const places = await refreshFloor();
  const active = places.filter((p) => p.active);
  const place = resolvePlace(active, d.script.reception) ?? active.find((p) => p.kind === "reception");
  if (!s.floor) return undefined;
  return (place ? spotOf(s.floor, place.spot) : undefined) ?? spotOf(s.floor, d.script.reception);
}

// the end of a run: the guide command is done; a walk that did not arrive (restart, stop, go_to) ends like the
// robot app ends it: the open leg failed with "The demo was ended by the team" (the server closes it calmly)
async function endRun(d: DemoRun, reason: string | null) {
  if (d.journeyId && reason) {
    const legId = await currentLegId(d.journeyId);
    const j = legId ? await getJourney(d.journeyId).catch(() => null) : null;
    if (j && legId && currentLeg(j).status !== "arrived") {
      await patch(d.journeyId, legId, { status: "failed", event: { type: "failed", detail: "The demo was ended by the team" } }).catch(() => undefined);
    }
  }
  if (d.commandId) await finishCommand(d.commandId).catch(() => undefined);
  await settleGuides(d).catch(() => undefined);
  state().walk = null;
}

async function demoRun(d: DemoRun) {
  const s = state();
  try {
    d.linePauseS = Number((await robotConfig(REAL_ROBOT)).settings.voice.linePauseS) || 0.8;
  } catch {
    d.linePauseS = 0.8;
  }
  let first = true;
  try {
    while (d.on) {
      try {
        d.restart = false;
        d.played = new Set();
        d.ticket = null;
        d.journeyId = null;
        d.commandId = null;
        d.pace = "normal";
        d.destName = null;
        // after a go_to otto holds there until resume (or restart)
        await holdPoint(d);
        // 1. to the reception (6. back there after a run, without talking). after a go_to: right there
        const spot = d.stayAt ?? (await receptionSpot(d));
        d.waitingFor = first ? "reception" : "back";
        d.nextPatientLine = upcomingPatientLine(d.script, null, 0, ["reception"]);
        await dbeat(d, { view: "idle", title: "Hello, I'm Otto", step: "" });
        if (spot) await demoDrive(d, spot);
        first = false;
        // 2. a person in front of otto (virtual otto sees one after a moment), or Next
        await demoWait(d, "person", d.script.start === "person" ? PERSON_SEEN_MS : 24 * 3600 * 1000);
        // 3.-5. reception, the walk with its cues, the arrival
        await playPart(d, "reception");
        await demoWalk(d);
        await playPart(d, "arrive");
        await endRun(d, null);
        d.runs += 1;
        note(`demo: run ${d.runs} done, back to the reception`);
      } catch (err) {
        if (err instanceof DemoRestart) {
          await endRun(d, "Demo restarted: the walk ended");
          d.stayAt = null;
          note("demo: restarted");
          continue;
        }
        if (err instanceof DemoStop) {
          await endRun(d, "Demo stopped: the walk ended");
          break;
        }
        if (err instanceof DemoGoTo) {
          // go_to: the run ends cleanly, otto drives there and holds. resume: the next person there. restart: reception
          await endRun(d, "Demo: sent somewhere else");
          const to = d.goTo;
          d.goTo = null;
          await refreshFloor();
          const there = to && s.floor ? spotOf(s.floor, to) : undefined;
          let stopped = false;
          if (there) {
            d.waitingFor = "back";
            await dbeat(d);
            try {
              await demoDrive(d, there);
              d.stayAt = there;
              d.hold = true;
              note(`demo: at ${there.name}, holds until resume or restart`);
            } catch (e) {
              if (e instanceof DemoStop) stopped = true;
              else if (e instanceof DemoRestart) d.stayAt = null;
              else if (!(e instanceof DemoGoTo)) throw e;
            }
          } else {
            note(`demo: ${to} is not on the map`);
            d.hold = true;
          }
          d.restart = false;
          if (stopped) break;
          continue;
        }
        throw err;
      }
    }
  } catch (err) {
    if (!(err instanceof Stopped)) note(`demo stopped: ${err instanceof Error ? err.message : "error"}`);
  } finally {
    d.on = false;
    d.hold = false;
    d.waitingFor = null;
    d.part = null;
    d.step = null;
    d.total = null;
    d.nextPatientLine = null;
    s.walk = null;
    if (s.on) await dbeat(d).catch(() => undefined);
    note("demo mode is off");
  }
}

// what Next does right now, for otto's answer
function nextText(d: DemoRun) {
  if (d.waitingFor === "person") return "Next: the reception part starts";
  if (d.waitingFor === "patient") return "Next: the patient's line is over";
  if (d.waitingFor === "close") return "Next: the patient is close again";
  if (d.waitingFor === "hold") return "On hold: press Resume to go on";
  if (d.waitingFor === "walk" && !d.part) {
    const part = WALK_PARTS.find((p) => !d.played.has(p));
    return part ? `Next: plays ${PART_INFO[part].title}` : "Next: nothing left to play on this walk";
  }
  return "Next: nothing to wait for right now";
}

// the demo command for virtual otto: { op, script?, text? } -> its answer { ok, detail }
function demoCommand(c: DemoCommand): { ok: boolean; detail: string } {
  const s = state();
  const d = s.demoRun;
  if (c.op === "say") {
    const t = c.text?.trim();
    if (!t) return { ok: false, detail: "Nothing to say" };
    if (d?.on) d.line = t;
    void say([{ who: "nova", kind: "said", text: t }]).catch(() => undefined);
    if (d?.on) void dbeat(d).catch(() => undefined);
    return { ok: true, detail: `Said "${t.slice(0, 80)}"` };
  }
  if (c.op === "start") {
    if (s.demo?.running) return { ok: false, detail: "Virtual Otto is playing the clinic tour on /simulate. Stop it first" };
    const checked = c.script ? checkScript(c.script) : null;
    const script = checked && !Object.keys(checked.errors).length ? checked.script : defaultScript();
    if (d?.on) {
      d.script = script;
      d.hold = false;
      d.restart = true;
      return { ok: true, detail: "Demo restarted with this script" };
    }
    const run: DemoRun = {
      script,
      on: true,
      part: null,
      step: null,
      total: null,
      waitingFor: "reception",
      line: null,
      nextPatientLine: null,
      ticket: null,
      runs: 0,
      hold: false,
      nextSeq: 0,
      restart: false,
      stop: false,
      played: new Set(),
      journeyId: null,
      commandId: null,
      guides: [],
      pace: "normal",
      linePauseS: 0.8,
      destName: null,
      goTo: null,
      stayAt: null,
    };
    s.demoRun = run;
    note(checked && Object.keys(checked.errors).length ? "demo: the script was broken, Virtual Otto uses the default" : "demo mode on");
    void demoRun(run);
    return { ok: true, detail: "Demo mode on: Virtual Otto drives to the reception" };
  }
  if (!d?.on) return c.op === "stop" ? { ok: true, detail: "Demo mode was off already" } : { ok: false, detail: "Demo mode is off. Press Start first" };
  if (c.op === "next") {
    const detail = nextText(d);
    d.nextSeq += 1;
    return { ok: true, detail };
  }
  if (c.op === "hold") {
    d.hold = true;
    return { ok: true, detail: "On hold before the next step" };
  }
  if (c.op === "resume") {
    d.hold = false;
    return { ok: true, detail: "Goes on" };
  }
  if (c.op === "restart") {
    d.hold = false;
    d.restart = true;
    return { ok: true, detail: "The walk ends, back to the reception for the next run" };
  }
  d.hold = false;
  d.stop = true;
  return { ok: true, detail: "Demo mode off" };
}

// ---------- switching on and off

async function cancelSimJourneys(reason: string) {
  const open = await getStore().listJourneys({ active: true });
  for (const j of open.filter((x) => x.robot === SIM_ROBOT)) {
    await addJourneyEvents(j.id, [event("simulation", reason)]).catch(() => undefined);
    await cancelJourney(j.id).catch(() => undefined);
  }
  // open commands of virtual otto go too
  const cmds = await getStore().listCommands(SIM_ROBOT, 50);
  for (const c of cmds) if (!c.doneAt) await finishCommand(c.id).catch(() => undefined);
}

export async function simOn(base: string) {
  const s = state();
  if (s.on && s.loop) return;
  s.on = true;
  s.base = base;
  s.speed = speedFactor();
  s.abort = new AbortController();
  s.queue = [];
  s.held = [];
  // leftovers of an earlier run (a restart in the middle) are not real patients
  await cancelSimJourneys("Ended: an earlier simulation was left open");
  note("Virtual Otto is on. It only takes jobs for otto-sim");
  loop().catch(() => undefined);
}

export async function simOff() {
  const s = state();
  s.on = false;
  if (s.demoRun?.on) s.demoRun.stop = true;
  s.abort.abort();
  if (s.demo?.running) {
    s.demo.running = false;
    s.demo.error = "Stopped";
  }
  s.walk = null;
  s.busy = null;
  s.queue = [];
  s.held = [];
  await cancelSimJourneys("Ended: the simulation was switched off");
  await beat({ state: "idle", goal: null, screen: { view: "idle", title: "Hello, I'm Otto", step: "Simulation off" } }).catch(() => undefined);
  note("Virtual Otto is off");
}

export function patientAction(action: unknown) {
  const s = state();
  if (!PATIENT_ACTIONS.includes(action as PatientAction)) throw new ApiError(400, `what must be one of ${PATIENT_ACTIONS.join(", ")}`);
  if (!s.on || !s.walk || s.walk.phase !== "walking") throw new ApiError(409, "Virtual Otto is not walking with a patient right now");
  if (s.queue.length >= 3) throw new ApiError(429, "One moment, Otto is still reacting to the last one");
  s.queue.push(action as PatientAction);
  return { ok: true };
}

// ---------- the whole demo

const STEPS: { key: string; title: string }[] = [
  { key: "checkin", title: "A patient checks in" },
  { key: "message", title: "The team gets a message" },
  { key: "pickup", title: "Otto picks the ticket up" },
  { key: "walk", title: "The walk, with check-backs" },
  { key: "arrive", title: "The patient sits in the waiting room" },
  { key: "callin", title: "The doctor taps Call in" },
  { key: "room", title: "Otto walks the patient to the room" },
  { key: "staff", title: "With the doctor" },
  { key: "exit", title: "Done: Otto walks the patient to the exit" },
  { key: "end", title: "Goodbye, the walk is closed" },
];

export function demoPlan(places: Place[]) {
  const active = places.filter((p) => p.active);
  const reception = active.find((p) => p.kind === "reception");
  const rooms = active.filter((p) => p.kind === "room" || p.kind === "other");
  const room =
    rooms.find((p) => p.selfCheckIn && p.waitingPlaceId && active.some((w) => w.id === p.waitingPlaceId)) ??
    rooms.find((p) => p.waitingPlaceId && active.some((w) => w.id === p.waitingPlaceId)) ??
    rooms.find((p) => p.selfCheckIn) ??
    rooms[0];
  const waiting = room?.waitingPlaceId ? active.find((p) => p.id === room.waitingPlaceId) : undefined;
  const exit = active.find((p) => p.kind === "exit");
  const missing = [...(reception ? [] : ["a reception"]), ...(room ? [] : ["a room"])];
  return { reception, room, waiting, exit, missing };
}

async function waitFor(check: () => Promise<boolean>, what: string, seconds = 120) {
  const until = Date.now() + seconds * 1000;
  while (Date.now() < until) {
    if (await check()) return;
    await sleep(300);
  }
  throw new Error(`waited too long for: ${what}`);
}

function step(key: string, st: DemoStep["state"], noteText?: string) {
  const d = state().demo;
  const s = d?.steps.find((x) => x.key === key);
  if (!s) return;
  s.state = st;
  if (noteText) s.note = noteText;
}

async function arrivedAt(journeyId: string, placeId: string) {
  const j = await getJourney(journeyId);
  const leg = currentLeg(j);
  return leg.toPlaceId === placeId && (leg.status === "arrived" || j.status === "with_staff");
}

async function playDemo() {
  const s = state();
  const places = await getStore().listPlaces();
  const plan = demoPlan(places);
  if (!plan.reception || !plan.room) return;
  const room = plan.room;
  const via = plan.waiting;
  const base = s.base;
  const d = s.demo!;
  if (!via) {
    step("arrive", "skipped", `${room.name} has no waiting room`);
    step("callin", "skipped", "Not needed without a waiting room");
  }
  if (!plan.exit) step("exit", "skipped", "No exit on the Places page");

  // 1. check-in: on otto's screen for a room open for self check-in, else reception sends the patient
  step("checkin", "now");
  const self = room.selfCheckIn;
  if (self) {
    await beat({ state: "greeting", screen: { view: "checkin", title: "Who are you here to see?", step: "Tap your doctor" } });
    await say([{ who: "nova", kind: "said", text: "Hello! Who are you here to see?" }, { who: "patient", kind: "intent", text: `picked ${room.name} on the screen` }]);
    await sleep(2000);
  }
  const created = await createJourney(
    { to: via ? via.id : room.id, ...(via ? { forPlaceId: room.id } : {}), pace: "slow", robot: SIM_ROBOT },
    self ? "robot" : "staff",
    base,
  );
  d.journeyId = created.journey.id;
  d.ticket = created.journey.ticket;
  note(`${d.ticket}: ${self ? "checked in on Virtual Otto's screen" : "reception sent the patient"} for ${room.name}`);
  step("checkin", "done", self ? `Ticket ${d.ticket}, on Otto's screen` : `Ticket ${d.ticket}, from the reception`);

  // 2. the message that went out with it
  step("message", "now");
  const j0 = await getJourney(d.journeyId);
  const sent = j0.events.filter((e) => /_(notified|sent)$/.test(e.type)).map((e) => e.detail ?? e.type);
  const failed = j0.events.filter((e) => /_failed$/.test(e.type)).length;
  const waitingMsg = via ? `The room gets "Your visitor has arrived" when ${d.ticket} sits in ${via.name}` : "";
  step(
    "message",
    "done",
    sent.length ? `${sent.length} sent${failed ? `, ${failed} failed` : ""}` : waitingMsg || (failed ? `${failed} failed` : "No channel set up yet: only this board"),
  );

  // 3-5. pickup, walk, arrival: virtual otto does this from its job list
  step("pickup", "now");
  await waitFor(async () => ["walking", "arrived", "with_staff"].includes((await getJourney(d.journeyId!)).status), "the pickup");
  step("pickup", "done");
  step("walk", "now");
  await sleep(2500);
  if (state().walk?.phase === "walking") state().queue.push("behind");
  const first = via ?? room;
  await waitFor(() => arrivedAt(d.journeyId!, first.id), `the arrival at ${first.name}`);
  step("walk", "done");
  if (via) {
    step("arrive", "done", `In ${via.name}, the message went to ${room.name}`);
    // 6. the doctor calls the patient in
    step("callin", "now");
    await sleep(4000);
    await nextLeg(d.journeyId, room.id, base, "board");
    note(`${d.ticket}: ${room.name} tapped Call in`);
    step("callin", "done");
    step("room", "now");
    await waitFor(() => arrivedAt(d.journeyId!, room.id), `the walk to ${room.name}`);
    step("room", "done");
  } else {
    step("room", "done", "Walked straight to the room");
  }

  // 8. with staff
  step("staff", "now");
  await sleep(1500);
  await patchJourney(d.journeyId, { status: "with_staff" }, "staff", base);
  note(`${d.ticket}: with ${room.name}`);
  await sleep(4000);
  step("staff", "done");

  // 9-10. exit and goodbye
  if (plan.exit) {
    step("exit", "now");
    await nextLeg(d.journeyId, plan.exit.id, base, "board");
    await waitFor(() => arrivedAt(d.journeyId!, plan.exit!.id), "the walk to the exit");
    await say([{ who: "nova", kind: "said", text: "Goodbye and get well soon!" }], d.journeyId);
    step("exit", "done");
  }
  step("end", "now");
  await finishJourney(d.journeyId);
  step("end", "done", `Ticket ${d.ticket} is closed. Its feed and data go with it`);
  note(`${d.ticket}: done, the demo is over`);
}

export async function startDemo(base: string) {
  const s = state();
  if (s.demo?.running) throw new ApiError(409, "The demo is already running");
  if (s.demoRun?.on) throw new ApiError(409, "Virtual Otto is in demo mode. Stop it on the Demo page first");
  const plan = demoPlan(await getStore().listPlaces());
  if (plan.missing.length) {
    throw new ApiError(409, `For the demo add ${plan.missing.join(" and ")} on Settings -> Places first`);
  }
  await simOn(base);
  s.demo = { steps: STEPS.map((x) => ({ ...x, state: "todo" })), running: true };
  note("The whole demo starts");
  (async () => {
    try {
      await playDemo();
    } catch (err) {
      if (s.demo) s.demo.error = err instanceof Stopped ? "Stopped" : err instanceof Error ? err.message : "The demo stopped";
      if (!(err instanceof Stopped)) note(`the demo stopped: ${s.demo?.error}`);
    } finally {
      if (s.demo) {
        s.demo.running = false;
        s.demo.finishedAt = now();
      }
    }
  })();
}

export async function stopDemo() {
  await simOff();
}

// ---------- what /simulate shows

export async function simView() {
  const s = state();
  const places = await getStore().listPlaces();
  const plan = demoPlan(places);
  if (!s.floor) await refreshFloor().catch(() => undefined);
  const missingOnMap = s.floor?.real ? s.floor.spots.filter((x) => x.generated).map((x) => x.name) : [];
  return {
    robot: SIM_ROBOT,
    on: s.on,
    speed: s.speed,
    busy: s.busy,
    walk: s.walk ? { ticket: s.walk.ticket, to: s.walk.to, phase: s.walk.phase, pace: s.walk.pace, progress: Math.round(s.walk.progress * 100) / 100 } : null,
    canAct: Boolean(s.on && s.walk && s.walk.phase === "walking"),
    demo: s.demo,
    // demo mode (the script on /demo), when it ran since the simulation is on
    demoMode: s.demoRun ? demoState(s.demoRun) : null,
    log: s.log.slice(-30).reverse(),
    floor: s.floor ? { mapName: s.floor.mapName, real: s.floor.real, missingOnMap } : null,
    plan: { reception: plan.reception?.name ?? null, room: plan.room?.name ?? null, waiting: plan.waiting?.name ?? null, exit: plan.exit?.name ?? null, missing: plan.missing },
  };
}
