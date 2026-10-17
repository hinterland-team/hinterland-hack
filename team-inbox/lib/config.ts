// otto's settings on the server (docs/robot-setup.md section 3): stored as the changes from the defaults with a
// version number, read by the robot (GET /api/robot/config), changed on Settings -> Robot. every save sends
// config_changed so otto fetches them right away; the heartbeat's configVersion (or the log line
// "Settings vN applied") tells when otto has them
import { now } from "./common";
import { ApiError } from "./http";
import { demoFlag, queueDemo } from "./demo";
import type { Caller } from "./http";
import { type OttoMode, type RobotMode, blockedText, isOttoMode } from "./modes";
import { type ConfigOverrides, type RobotSettings, applyChange, mergeSettings } from "./robot-config";
import { isSimRobot } from "./robots";
import { getStore } from "./store";
import { ROBOT_ONLINE_SECONDS, type RobotCommand } from "./types";

type Saved = { version: number; overrides: ConfigOverrides; updatedAt: string | null };
export type Applied = { version: number; at: string };

const key = (robot: string) => `robot:config:${robot}`;
const appliedKey = (robot: string) => `robot:config:applied:${robot}`;

async function readSaved(robot: string): Promise<Saved> {
  const raw = await getStore().getSetting(key(robot));
  if (raw) {
    try {
      const v = JSON.parse(raw) as Partial<Saved>;
      if (typeof v.version === "number" && v.overrides && typeof v.overrides === "object") {
        return { version: v.version, overrides: v.overrides, updatedAt: typeof v.updatedAt === "string" ? v.updatedAt : null };
      }
    } catch {
      // broken: the defaults
    }
  }
  return { version: 0, overrides: {}, updatedAt: null };
}

export async function readApplied(robot: string): Promise<Applied | null> {
  const raw = await getStore().getSetting(appliedKey(robot));
  if (!raw) return null;
  try {
    const v = JSON.parse(raw) as Partial<Applied>;
    return typeof v.version === "number" && typeof v.at === "string" ? { version: v.version, at: v.at } : null;
  } catch {
    return null;
  }
}

// what the robot gets: { version, settings } with every setting, defaults filled in
export async function robotConfig(robot: string): Promise<{ version: number; settings: RobotSettings }> {
  const saved = await readSaved(robot);
  return { version: saved.version, settings: mergeSettings(saved.overrides).settings };
}

export type ConfigView = {
  robot: string;
  version: number;
  settings: RobotSettings;
  overrides: ConfigOverrides;
  updatedAt: string | null;
  applied: Applied | null;
};

export async function configView(robot: string): Promise<ConfigView> {
  const [saved, applied] = await Promise.all([readSaved(robot), readApplied(robot)]);
  return {
    robot,
    version: saved.version,
    settings: mergeSettings(saved.overrides).settings,
    overrides: saved.overrides,
    updatedAt: saved.updatedAt,
    applied,
  };
}

// one robot command of a kind that is waiting anyway is enough (config_changed, recheck)
export async function queueOnce(robot: string, type: "config_changed" | "recheck"): Promise<RobotCommand> {
  const store = getStore();
  const open = (await store.listCommands(robot, 20)).find((c) => c.type === type && !c.deliveredAt && !c.doneAt);
  if (open) return open;
  return store.createCommand({ id: crypto.randomUUID(), robot, type, createdAt: now() });
}

// a new mode replaces a set_mode otto has not fetched yet
async function queueMode(robot: string, mode: RobotMode) {
  const store = getStore();
  const at = now();
  for (const c of await store.listCommands(robot, 20)) {
    if (c.type === "set_mode" && !c.deliveredAt && !c.doneAt) await store.finishCommand(c.id, at);
  }
  return store.createCommand({ id: crypto.randomUUID(), robot, type: "set_mode", mode, createdAt: at });
}

// staff: { settings?: { mode?, group: { key: value } }, reset?: group | "all" } -> new version.
// otto hears about it: set_mode for a new mode, config_changed for the other settings
export async function changeConfig(robot: string, body: Record<string, unknown>): Promise<ConfigView> {
  const saved = await readSaved(robot);
  const { overrides, errors, changed } = applyChange(saved.overrides, body.settings, body.reset);
  const first = Object.values(errors)[0];
  if (first) throw new ApiError(400, first);
  if (changed) {
    const next: Saved = { version: saved.version + 1, overrides, updatedAt: now() };
    await getStore().setSetting(key(robot), JSON.stringify(next));
    const modeBefore = saved.overrides.mode ?? "normal";
    const modeNow = overrides.mode ?? "normal";
    const { mode: _a, ...restBefore } = saved.overrides;
    const { mode: _b, ...restNow } = overrides;
    void _a;
    void _b;
    if (modeBefore !== modeNow) await queueMode(robot, modeNow);
    // a new mode alone too: otto fetches the new version right away, so Settings shows it applied soon
    if (modeBefore !== modeNow || JSON.stringify(restBefore) !== JSON.stringify(restNow)) await queueOnce(robot, "config_changed");
  }
  return configView(robot);
}

// ---------- modes (lib/modes.ts): switched in the header, part of the settings as settings.mode

export async function savedMode(robot: string): Promise<RobotMode> {
  return (await readSaved(robot)).overrides.mode ?? "normal";
}

const modeKey = (robot: string) => `robot:mode:applied:${robot}`;

// the heartbeat's mode: otto runs it since `at` (only written when it changes)
export async function noteMode(robot: string, mode: string, at: string) {
  if (!isOttoMode(mode)) return;
  const raw = await getStore().getSetting(modeKey(robot));
  try {
    if (raw && (JSON.parse(raw) as { mode?: string }).mode === mode) return;
  } catch {
    // broken: written again
  }
  await getStore().setSetting(modeKey(robot), JSON.stringify({ mode, at }));
}

export type ModeView = {
  robot: string;
  // the mode that counts (header, new walks): the dashboard's choice, or the one picked on otto itself (onRobot)
  mode: OttoMode;
  // the dashboard's choice: settings.mode, or demo while the dashboard runs demo mode
  saved: OttoMode;
  // the mode otto reported, since when
  applied: { mode: OttoMode; at: string } | null;
  // otto takes this mode when the walk under way is over (heartbeat modePending)
  pending: OttoMode | null;
  // the dashboard's choice has not reached otto yet
  switching: boolean;
  // otto runs (or waits for) a mode picked on its own staff menu
  onRobot: boolean;
  // otto's answer when it refused the last switch
  refused: string | null;
  online: boolean;
};

export async function modeView(robot: string): Promise<ModeView> {
  const store = getStore();
  const [settingsMode, raw, { lastSeenAt, heartbeat }, commands, demoOn] = await Promise.all([
    savedMode(robot),
    store.getSetting(modeKey(robot)),
    store.getRobot(robot),
    store.listCommands(robot, 30),
    demoFlag(robot),
  ]);
  const saved: OttoMode = demoOn ? "demo" : settingsMode;
  let applied: { mode: OttoMode; at: string } | null = null;
  try {
    const v = raw ? (JSON.parse(raw) as { mode?: unknown; at?: unknown }) : null;
    if (v && isOttoMode(v.mode) && typeof v.at === "string") applied = { mode: v.mode, at: v.at };
  } catch {
    applied = null;
  }
  const seen = [lastSeenAt, heartbeat?.at].filter((t): t is string => Boolean(t)).sort().at(-1);
  const online = Boolean(seen && Date.now() - Date.parse(seen) <= ROBOT_ONLINE_SECONDS * 1000);
  // the newest switch (set_mode, demo start or stop) is on its way until otto answered it (a newer choice closes
  // older ones it did not fetch)
  const last = commands.find((c) => c.type === "set_mode" || (c.type === "demo" && (c.op === "start" || c.op === "stop")));
  const inFlight = Boolean(last && !last.doneAt);
  // what otto runs, and the mode it takes after the walk under way (only while it is online)
  const inDemo = heartbeat?.demo?.running === true || heartbeat?.mode === "demo";
  const reported: OttoMode | null = !online ? null : inDemo ? "demo" : isOttoMode(heartbeat?.mode) ? heartbeat.mode : null;
  const later = reported && isOttoMode(heartbeat?.modePending) && heartbeat.modePending !== reported ? heartbeat.modePending : null;
  const wants = reported === null ? null : (later ?? reported);
  // otto's report counts when it shows the dashboard's choice, or came after otto's answer to the last switch
  const fresh = wants !== null && (wants === saved || (!inFlight && (!last?.doneAt || (heartbeat?.at ?? "") >= last.doneAt)));
  const onRobot = fresh && wants !== saved;
  return {
    robot,
    mode: onRobot && wants ? wants : saved,
    saved,
    applied,
    pending: fresh ? later : null,
    switching: !fresh && (wants !== null || inFlight || (applied?.mode ?? "normal") !== saved),
    onRobot,
    refused: last?.ok === false && wants !== saved ? (last.detail ?? "Otto refused it") : null,
    online,
  };
}

export async function setMode(robot: string, mode: unknown) {
  if (!isOttoMode(mode)) throw new ApiError(400, "mode must be normal, show, calm or demo");
  // demo: the demo command with the saved script (settings.mode stays what it was)
  if (mode === "demo") {
    await queueDemo(robot, "start");
    return modeView(robot);
  }
  // leaving demo mode: demo stop first, then the chosen mode
  if ((await demoFlag(robot)) || (await modeView(robot)).mode === "demo") await queueDemo(robot, "stop");
  const before = await savedMode(robot);
  await changeConfig(robot, { settings: { mode } });
  // the same choice again: otto may run a mode from its own staff menu, so it gets the switch anyway
  if (before === mode) await queueMode(robot, mode);
  return modeView(robot);
}

// new walks start only in normal mode (the robot refuses guides in show, calm and demo too), also when the mode
// was picked on otto's staff menu. in demo mode otto's own check-in (who = robot) is part of the script
export async function assertCanGuide(robot: string, who?: Caller) {
  if (isSimRobot(robot)) return;
  const { mode } = await modeView(robot);
  if (mode === "normal" || (mode === "demo" && who === "robot")) return;
  throw new ApiError(409, blockedText(mode));
}

// the heartbeat's configVersion or the log line "Settings v12 applied": otto runs that version since `at`
export async function noteApplied(robot: string, version: number, at: string) {
  if (!Number.isInteger(version) || version < 0) return;
  const before = await readApplied(robot);
  if (before && before.version === version) return;
  await getStore().setSetting(appliedKey(robot), JSON.stringify({ version, at }));
}

const APPLIED_LINE = /\bSettings v(\d{1,9}) applied\b/i;

export function appliedFromLog(text: string) {
  const m = APPLIED_LINE.exec(text);
  return m ? Number(m[1]) : null;
}
