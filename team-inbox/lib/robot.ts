import { notifyHelp } from "./channels";
import { hoursAgo, now, numberOrNull, retention, robotName, today } from "./common";
import { noteApplied, noteMode, queueOnce, setMode } from "./config";
import { queueDemo, withoutScript } from "./demo";
import { cleanDemoState } from "./demo-script";
import { isOttoMode } from "./modes";
import { isSimRobot } from "./robots";
import { ApiError, text } from "./http";
import { markAssigned } from "./journeys";
import { feedId, readFeed, saveFeed } from "./live-feed";
import { helpMessage } from "./messages";
import { rememberMaps } from "./floors";
import { cleanMapFrame, cleanMaps } from "./robot-data";
import { getStore } from "./store";
import {
  FEED_KINDS,
  FEED_WHO,
  type CommandResult,
  type FeedItem,
  type HelpRequest,
  type RobotCommand,
  type RobotScreen,
  MAP_TRAIL_MAX,
  SCREEN_VIEWS,
  WALK_ACTIONS,
  isOneOf,
  normalizeTicket,
  robotStatus,
  summarize,
} from "./types";

export const SHOW_PATIENT_TEXT = "showPatientText";

// ---------- commands

// a poll also counts as seen for the online dot
export async function nextCommand(robot: string) {
  const store = getStore();
  const at = now();
  await store.touchRobot(robot, at);
  const command = await store.takeNextCommand(robot, at);
  if (command?.type === "guide") await markAssigned(command);
  return command;
}

// body = otto's answer { ok, detail } for commands it can refuse or that can fail (switch_map, set_mode, recheck,
// a walk or words in show / calm mode); an empty body for the rest, as before
export async function finishCommand(id: string, body?: Record<string, unknown>) {
  const result: CommandResult | undefined =
    typeof body?.ok === "boolean" ? { ok: body.ok, ...(text(body.detail, 200) ? { detail: text(body.detail, 200) } : {}) } : undefined;
  const command = await getStore().finishCommand(id, now(), result);
  if (!command) throw new ApiError(404, "command not found");
  return command;
}

// the walk otto is on right now: a guide it fetched and did not finish
export async function guidingNow(robot: string) {
  const list = await getStore().listCommands(robot, 30);
  const g = list.find((c) => c.type === "guide" && c.deliveredAt && !c.doneAt);
  return g?.type === "guide" ? g : null;
}

// staff: ask = otto's ai decides what to do with it, say = word for word, go_to = drive there and stay,
// switch_map = another floor map (spot = where otto stands on it), config_changed / recheck (robot-setup.md)
export async function createRobotCommand(body: Record<string, unknown>): Promise<RobotCommand> {
  await retention();
  const id = crypto.randomUUID();
  const robot = robotName(body.robot);
  const createdAt = now();
  if (body.type === "say" || body.type === "ask") {
    const t = text(body.text, body.type === "ask" ? 500 : 300);
    if (!t) throw new ApiError(400, "text is required");
    return getStore().createCommand({ id, robot, type: body.type, text: t, createdAt });
  }
  if (body.type === "go_to") {
    const spot = text(body.spot, 80);
    if (!spot) throw new ApiError(400, "spot is required");
    return getStore().createCommand({ id, robot, type: "go_to", spot, createdAt });
  }
  if (body.type === "switch_map") {
    const mapName = text(body.mapName, 80);
    const spot = text(body.spot, 80);
    if (!mapName) throw new ApiError(400, "mapName is required");
    if (!spot) throw new ApiError(400, "spot is required: where Otto stands on that map, so it knows where it is");
    const walk = await guidingNow(robot);
    if (walk) throw new ApiError(409, `Otto is walking with ${walk.ticket} right now. Switch the floor after this walk`);
    return getStore().createCommand({ id, robot, type: "switch_map", mapName, spot, createdAt });
  }
  if (body.type === "config_changed" || body.type === "recheck") return queueOnce(robot, body.type);
  if (body.type === "set_mode") {
    // the mode is part of the settings: saved there, then set_mode goes to otto (also for the same mode again).
    // demo goes as the demo command with the saved script
    await setMode(robot, body.mode);
    const demo = body.mode === "demo";
    const sent = (await getStore().listCommands(robot, 10)).find(
      (c) => (demo ? c.type === "demo" && c.op === "start" : c.type === "set_mode") && !c.deliveredAt && !c.doneAt,
    );
    if (!sent) throw new ApiError(500, "the mode switch was not queued");
    return sent;
  }
  // demo mode (docs/demo-mode.md): { type: "demo", op, text? }, a start carries the saved script
  if (body.type === "demo") return queueDemo(robot, body.op, body.text);
  if (body.type === "walk_control") throw new ApiError(400, `walk_control needs journeyId and action (${WALK_ACTIONS.join(", ")})`);
  throw new ApiError(400, "type must be ask, say, go_to, walk_control, switch_map, config_changed, recheck, set_mode or demo");
}

// what a patient asked on the phone only shows with the demo switch on (and is gone once otto has it)
export async function listCommands(robot: string, limit: number, showPatientWords?: boolean) {
  const show = showPatientWords ?? (await showPatientText());
  const list = await getStore().listCommands(robot, Math.min(50, Math.max(1, Math.round(limit) || 10)));
  return list.map((c) => (c.type === "ask" && c.origin === "phone" && !show ? { ...c, text: "" } : withoutScript(c)));
}

// ---------- heartbeat

function cleanScreen(value: unknown): RobotScreen | null {
  if (!value || typeof value !== "object") return null;
  const s = value as Record<string, unknown>;
  const view = typeof s.view === "string" ? s.view.toLowerCase() : "";
  const screen: RobotScreen = {
    view: isOneOf(SCREEN_VIEWS, view) ? view : "idle",
    title: text(s.title, 120) ?? "",
  };
  const ticket = normalizeTicket(s.ticket);
  if (ticket) screen.ticket = ticket;
  const step = text(s.step, 160);
  if (step) screen.step = step;
  const progress = numberOrNull(s.progress);
  if (progress !== null) screen.progress = Math.round(Math.min(1, Math.max(0, progress)) * 100) / 100;
  return screen;
}

// a heartbeat pose keeps the map alive when no map frame came for a while (map frames win: they have more)
export const HEARTBEAT_POSE_AFTER_SECONDS = 20;

// the robot reports every 15 s (5 s while moving). never refused for odd values: they are stored as null
export async function saveHeartbeat(body: Record<string, unknown>) {
  const battery = numberOrNull(body.battery);
  const robot = robotName(body.robot);
  if (body.pose && typeof body.pose === "object") {
    const at = now();
    const cleaned = cleanMapFrame({ pose: body.pose }, robot, at);
    if ("frame" in cleaned) {
      const map = await getStore().getMap(robot);
      if (!map || Date.parse(at) - Date.parse(map.updatedAt) > HEARTBEAT_POSE_AFTER_SECONDS * 1000) {
        await getStore().saveMap(cleaned.frame, MAP_TRAIL_MAX);
      }
    }
  }
  // robot-setup.md: the floor map, localised on it (either spelling), the settings version it runs
  const localised = typeof body.localised === "boolean" ? body.localised : typeof body.localized === "boolean" ? body.localized : null;
  const configVersion = numberOrNull(body.configVersion);
  // demo mode: where otto is in the script, exactly the fields of docs/demo-mode.md
  const demo = cleanDemoState(body.demo);
  const at = now();
  await getStore().saveHeartbeat({
    robot,
    battery: battery === null ? null : Math.round(Math.min(100, Math.max(0, battery))),
    charging: body.charging === true,
    state: text(body.state, 40)?.toLowerCase() ?? null,
    position: text(body.position, 80) ?? null,
    goal: text(body.goal, 200) ?? null,
    detail: text(body.detail, 300) ?? null,
    screen: cleanScreen(body.screen),
    server: text(body.server, 200) ?? null,
    version: text(body.version, 40) ?? null,
    mapName: text(body.mapName, 80) ?? null,
    localised,
    configVersion: configVersion !== null && Number.isInteger(configVersion) && configVersion >= 0 ? configVersion : null,
    mode: isOttoMode(body.mode) ? body.mode : null,
    modePending: isOttoMode(body.modePending) ? body.modePending : null,
    demo,
    at,
  });
  if (configVersion !== null) await noteApplied(robot, configVersion, at);
  const runs = demo?.running ? "demo" : body.mode;
  if (isOttoMode(runs)) await noteMode(robot, runs, at);
  const maps = cleanMaps(body.maps);
  if (maps?.length) await rememberMaps(robot, maps);
}

export async function getRobotStatus(robot: string) {
  const { lastSeenAt, heartbeat } = await getStore().getRobot(robot);
  return robotStatus(robot, lastSeenAt, heartbeat, Date.now());
}

// ---------- help

export async function requestHelp(body: Record<string, unknown>, base: string) {
  await retention();
  const summary = text(body.summary, 500);
  if (!summary) throw new ApiError(400, "summary is required");
  const store = getStore();
  const help: HelpRequest = {
    id: crypto.randomUUID(),
    robot: robotName(body.robot),
    summary,
    urgency: body.urgency === "urgent" ? "urgent" : "normal",
    status: "open",
    notified: false,
    createdAt: now(),
  };
  const journeyId = text(body.journeyId, 80);
  if (journeyId) help.journeyId = journeyId;
  const ticket = normalizeTicket(body.ticket);
  if (ticket) help.ticket = ticket;
  await store.createHelp(help);
  // to the default channels (the practice teams channel and the ones marked default on /notifications)
  const notified = await notifyHelp(helpMessage(help, `${base}/`));
  if (notified) await store.setHelpNotified(help.id, true);
  return { help: { ...help, notified }, notified };
}

export async function updateHelp(id: string, status: unknown) {
  await retention();
  if (status !== "open" && status !== "done") throw new ApiError(400, "status must be open or done");
  const help = await getStore().updateHelp(id, status, now());
  if (!help) throw new ApiError(404, "help request not found");
  return help;
}

// ---------- live feed (memory only)

export async function showPatientText() {
  return (await getStore().getSetting(SHOW_PATIENT_TEXT)) === "true";
}

export async function setShowPatientText(value: unknown) {
  if (typeof value !== "boolean") throw new ApiError(400, "showPatientText must be true or false");
  await getStore().setSetting(SHOW_PATIENT_TEXT, String(value));
  return value;
}

// what the patient said word for word is only kept while the demo switch is on
export async function postFeed(body: Record<string, unknown>) {
  const robot = robotName(body.robot);
  const journeyId = text(body.journeyId, 80);
  const raw = Array.isArray(body.items) ? body.items.slice(0, 50) : null;
  if (!raw) throw new ApiError(400, "items must be a list");
  const show = await showPatientText();
  const at = now();
  const items: FeedItem[] = [];
  let dropped = 0;
  for (const r of raw as Record<string, unknown>[]) {
    if (!r || typeof r !== "object") continue;
    const who = r.who;
    const kind = r.kind;
    const t = text(r.text, 300);
    if (!isOneOf(FEED_WHO, who) || !isOneOf(FEED_KINDS, kind) || !t) {
      throw new ApiError(400, "each item needs who (otto or patient), kind (said, intent or action) and text");
    }
    if (who === "patient" && kind === "said" && !show) {
      dropped++;
      continue;
    }
    const when = typeof r.at === "string" && !Number.isNaN(Date.parse(r.at)) ? new Date(r.at).toISOString() : at;
    const clamped = when > at ? at : when;
    // older than the 12 h the feed may keep
    if (clamped < hoursAgo(12)) {
      dropped++;
      continue;
    }
    items.push({
      id: feedId(),
      robot,
      ...(journeyId ? { journeyId } : {}),
      at: clamped,
      who,
      kind,
      text: t,
    });
  }
  await saveFeed(robot, items);
  return { ok: true, stored: items.length, dropped, showPatientText: show };
}

export async function getFeed(robot: string, limit = 50) {
  return { items: await readFeed(robot, Math.min(200, Math.max(1, limit))), showPatientText: await showPatientText() };
}

// ---------- stats

export async function stats(day = today()) {
  const store = getStore();
  const [rows, places, open] = await Promise.all([store.getStats(day), store.listPlaces(), store.listJourneys({ active: true })]);
  // virtual otto's walks are not counted (today's numbers are real ones only)
  return summarize(day, rows, places, open.filter((j) => !isSimRobot(j.robot)).length);
}
