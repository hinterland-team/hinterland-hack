// otto's settings that change without a new build (docs/robot-setup.md section 3): every setting with its default,
// range and a one-line help text. shared by the server (checks, GET /api/robot/config) and Settings -> Robot.
// the names, defaults and ranges are the robot app's (RobotSettings.kt), so otto takes every value the form allows.
// only lib/modes.ts imported, tests load this file directly
import { ROBOT_MODES, type RobotMode } from "./modes";

export type ConfigGroupKey = "walk" | "way" | "voice" | "sensors" | "screen";
// null = "as on the robot" (volume: leave it as set there, call languages: otto's built-in ones)
export type ConfigValue = number | boolean | string | string[] | null;

type Base = { group: ConfigGroupKey; key: string; label: string; help: string };
export type ConfigField =
  | (Base & {
      type: "number";
      default: number | null;
      min: number;
      max: number;
      step: number;
      unit?: string;
      integer?: boolean;
      // empty is allowed and means emptyLabel
      optional?: boolean;
      emptyLabel?: string;
    })
  | (Base & { type: "bool"; default: boolean })
  | (Base & { type: "choice"; default: string; options: { value: string; label: string }[] })
  | (Base & { type: "text"; default: string; maxLength: number })
  | (Base & {
      type: "list";
      default: string[] | null;
      maxItems: number;
      pattern: string;
      patternHelp: string;
      optional?: boolean;
      emptyLabel?: string;
    });

export const CONFIG_GROUPS: { key: ConfigGroupKey; title: string; help: string }[] = [
  { key: "walk", title: "Walking with a patient", help: "How fast Otto walks, how often it checks on the patient and how long it waits." },
  { key: "way", title: "Blocked ways", help: "What Otto does when people or things stand in the way." },
  { key: "voice", title: "Voice and speech", help: "How loud Otto is, which languages it calls tickets in and how it paces what it says." },
  { key: "sensors", title: "Sensors and data", help: "What Otto watches and sends to this dashboard." },
  { key: "screen", title: "Screen", help: "Texts on Otto's screen." },
];

// robot navigation takes 0.1 to 0.85 m/s (RobotAPI "Navigate to Specified Location")
export const MIN_SPEED = 0.1;
export const MAX_SPEED = 0.85;

// otto's privacy line during a walk (the robot app's built-in text)
export const PRIVACY_LINE = "Otto only knows your ticket and the room. Nothing is recorded.";

// BCP-47 language tags like the robot app takes them: de, en, de-DE, zh-Hans-CN ("_" is read as "-")
export const LANGUAGE_TAG = "^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$";

const sec = (
  group: ConfigGroupKey,
  key: string,
  label: string,
  help: string,
  def: number,
  min: number,
  max: number,
): ConfigField => ({ group, key, type: "number", label, help, default: def, min, max, step: 1, unit: "s", integer: true });

export const CONFIG_FIELDS: ConfigField[] = [
  // walk
  { group: "walk", key: "speedNormal", type: "number", label: "Speed, normal pace", help: "Walking speed at the normal pace.", default: 0.6, min: MIN_SPEED, max: MAX_SPEED, step: 0.05, unit: "m/s" },
  { group: "walk", key: "speedSlow", type: "number", label: "Speed, slow pace", help: "For patients who walk slowly, and for wheelchairs.", default: 0.4, min: MIN_SPEED, max: MAX_SPEED, step: 0.05, unit: "m/s" },
  { group: "walk", key: "speedVerySlow", type: "number", label: "Speed, very slow pace", help: "For patients who need a lot of time.", default: 0.25, min: MIN_SPEED, max: MAX_SPEED, step: 0.05, unit: "m/s" },
  {
    group: "walk",
    key: "walkSide",
    type: "choice",
    label: "Patient walks on",
    help: "The side the patient walks on, next to Otto. Otto's radar sees the sides, not the back.",
    default: "left",
    options: [
      { value: "left", label: "Otto's left" },
      { value: "right", label: "Otto's right" },
    ],
  },
  {
    group: "walk",
    key: "turnSide",
    type: "choice",
    label: "Turn around to look back",
    help: "Which way Otto turns around to look at the patient during a walk.",
    default: "right",
    options: [
      { value: "right", label: "Right" },
      { value: "left", label: "Left" },
      { value: "shortest", label: "Shortest way" },
    ],
  },
  sec("walk", "firstCheckS", "First check after", "The first check on the patient after the walk starts.", 10, 3, 60),
  sec("walk", "checkMinS", "Shortest time between checks", "When the patient is far behind.", 10, 5, 120),
  sec("walk", "checkMaxS", "Longest time between checks", "When the patient walks right beside Otto.", 30, 5, 180),
  sec("walk", "besideLostS", "Nobody beside for", "Nobody next to Otto on the radar this long while it drives: it checks at once.", 5, 2, 30),
  {
    group: "walk",
    key: "askEvery",
    type: "number",
    label: "Ask \"All good?\" every",
    help: "At every n-th stop Otto asks the patient, at the others it only says a short line. 0 = only when the patient fell behind.",
    default: 2,
    min: 0,
    max: 10,
    step: 1,
    unit: "stops",
    integer: true,
  },
  sec("walk", "listenS", "Listen for an answer", "How long Otto listens for an answer at a stop.", 5, 2, 20),
  sec("walk", "pickupTimeoutS", "Wait at pickup", "How long Otto waits for a called ticket before it gives up.", 180, 30, 900),
  sec("walk", "pickupRemindS", "Call the ticket again every", "While Otto waits at the pickup point.", 45, 10, 300),
  sec("walk", "farWaitS", "Wait for a patient far behind", "How long Otto waits for a patient who is a few metres behind.", 20, 5, 120),
  sec("walk", "lostCallAgainS", "Lost patient: call again after", "Nobody near Otto: it calls the ticket again after this long.", 12, 3, 120),
  sec("walk", "lostReportS", "Lost patient: tell the team after", "Still nobody: Otto tells the team after this long.", 30, 10, 300),
  sec("walk", "lostGiveUpS", "Lost patient: go back after", "After telling the team, Otto waits this long more, then goes back.", 60, 10, 600),
  // way
  sec("way", "blockedS", "Blocked before another way", "Blocked this long on one try: Otto tries another way.", 25, 5, 180),
  { group: "way", key: "backOff", type: "bool", label: "Step back and try again", help: "First back off a little and try the same way again, only when nobody is behind Otto.", default: true },
  { group: "way", key: "autoWaypoints", type: "bool", label: "Find other ways on its own", help: "Detours over other spots of the map that lead around the blocked part.", default: true },
  { group: "way", key: "maxWaypoints", type: "number", label: "Most waypoints", help: "How many waypoints Otto tries on one leg.", default: 2, min: 0, max: 5, step: 1, integer: true },
  sec("way", "helpAfterS", "Call the team after", "Blocked this long on one leg in total: Otto calls the team, also before it tried every detour.", 90, 20, 600),
  // voice
  {
    group: "voice",
    key: "volume",
    type: "number",
    label: "Volume",
    help: "Otto's speaker. Empty = leave it as set on the robot.",
    default: null,
    min: 0,
    max: 100,
    step: 5,
    unit: "%",
    integer: true,
    optional: true,
    emptyLabel: "as set on the robot",
  },
  {
    group: "voice",
    key: "callLanguages",
    type: "list",
    label: "Ticket call languages",
    help: "Otto calls a ticket in these languages until the patient speaks, in this order, e.g. de, en, tr. Empty = Otto's built-in languages.",
    default: null,
    maxItems: 6,
    pattern: LANGUAGE_TAG,
    patternHelp: "language tags like de, en or de-DE",
    optional: true,
    emptyLabel: "Otto's built-in languages",
  },
  { group: "voice", key: "styleNote", type: "text", label: "Extra note for Otto's speaking style", help: "Added to how Otto talks, e.g. \"speak a bit slower\". Never patient details.", default: "", maxLength: 300 },
  { group: "voice", key: "hideVoiceBar", type: "bool", label: "Hide the system voice bar", help: "Hides the robot's own voice bar while Otto's screen is in front.", default: true },
  { group: "voice", key: "openMicOnWalks", type: "bool", label: "Listen during walks", help: "During walks Otto listens without anyone facing it, because the patient walks beside it.", default: true },
  { group: "voice", key: "linePauseS", type: "number", label: "Pause between lines", help: "A short pause between two things Otto says.", default: 0.8, min: 0, max: 5, step: 0.1, unit: "s" },
  { group: "voice", key: "answerWaitS", type: "number", label: "Wait for an answer", help: "After a question Otto waits this long for an answer before it says anything else.", default: 7, min: 2, max: 30, step: 0.5, unit: "s" },
  { group: "voice", key: "afterPatientS", type: "number", label: "Pause after the patient spoke", help: "Otto waits this long after the patient's last word.", default: 0.7, min: 0, max: 3, step: 0.1, unit: "s" },
  { group: "voice", key: "maxWaitingLines", type: "number", label: "Lines that may wait", help: "How many lines may wait while Otto is talking. Older or less important ones are dropped.", default: 1, min: 1, max: 5, step: 1, integer: true },
  // sensors
  { group: "sensors", key: "radarWatch", type: "bool", label: "Watch the patient with the radar", help: "Nobody beside Otto makes it check at once, and it skips a stop when it sees the patient beside it.", default: true },
  sec("sensors", "mapMovingS", "Live map while driving every", "How often Otto sends its position to the map while it drives.", 2, 1, 30),
  sec("sensors", "mapIdleS", "Live map while standing every", "How often Otto sends its position while it stands.", 15, 5, 120),
  { group: "sensors", key: "debugLogs", type: "bool", label: "Send debug log lines", help: "More lines on the Logs page, for finding problems. Otto's staff menu always shows them.", default: false },
  // screen
  { group: "screen", key: "privacyLine", type: "text", label: "Privacy line", help: "Shown on Otto's screen during a walk. Empty = no line.", default: PRIVACY_LINE, maxLength: 140 },
  { group: "screen", key: "talkHints", type: "bool", label: "Show talk hints", help: "Small \"just talk to me\" hints on Otto's screen.", default: true },
];

// the mode (normal / show / calm, lib/modes.ts) is part of the settings: settings.mode
const MODES = ROBOT_MODES;
type Mode = RobotMode;

export type RobotSettings = { mode: Mode } & Record<ConfigGroupKey, Record<string, ConfigValue>>;
// what is stored: only the values that differ from the defaults
export type ConfigOverrides = { mode?: Mode } & Partial<Record<ConfigGroupKey, Record<string, ConfigValue>>>;

export function fieldsOf(group: ConfigGroupKey) {
  return CONFIG_FIELDS.filter((f) => f.group === group);
}

export function findField(group: string, key: string) {
  return CONFIG_FIELDS.find((f) => f.group === group && f.key === key);
}

const isModeValue = (v: unknown): v is Mode => typeof v === "string" && (MODES as readonly string[]).includes(v);

export function defaultSettings(): RobotSettings {
  const out = { mode: "normal", walk: {}, way: {}, voice: {}, sensors: {}, screen: {} } as RobotSettings;
  for (const f of CONFIG_FIELDS) out[f.group][f.key] = Array.isArray(f.default) ? [...f.default] : f.default;
  return out;
}

function same(a: ConfigValue, b: ConfigValue) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// a value for the form and the notes: 0.6, "on", "de, en", or the field's empty label
export function showValue(f: ConfigField, v: ConfigValue): string {
  if (v === null || v === undefined) return "emptyLabel" in f && f.emptyLabel ? f.emptyLabel : "empty";
  if (Array.isArray(v)) return v.join(", ");
  if (typeof v === "boolean") return v ? "on" : "off";
  if (f.type === "choice") return f.options.find((o) => o.value === v)?.label ?? String(v);
  if (f.type === "text") return v === "" ? "no text" : `"${v}"`;
  return `${v}${f.type === "number" && f.unit ? ` ${f.unit}` : ""}`;
}

// one value checked against its field. { value } or { error } (a sentence for the form)
export function checkValue(f: ConfigField, raw: unknown): { value: ConfigValue } | { error: string } {
  switch (f.type) {
    case "number": {
      if (f.optional && (raw === null || raw === undefined || (typeof raw === "string" && !raw.trim()))) return { value: null };
      const n = typeof raw === "number" ? raw : typeof raw === "string" && raw.trim() ? Number(raw) : NaN;
      if (!Number.isFinite(n)) return { error: `${f.label} must be a number` };
      const v = f.integer ? Math.round(n) : Math.round(n * 1000) / 1000;
      if (v < f.min || v > f.max) return { error: `${f.label} must be from ${f.min} to ${f.max}${f.unit ? ` ${f.unit}` : ""}` };
      return { value: v };
    }
    case "bool":
      return typeof raw === "boolean" ? { value: raw } : { error: `${f.label} must be on or off` };
    case "choice":
      return typeof raw === "string" && f.options.some((o) => o.value === raw)
        ? { value: raw }
        : { error: `${f.label} must be one of ${f.options.map((o) => o.value).join(", ")}` };
    case "text": {
      if (typeof raw !== "string") return { error: `${f.label} must be text` };
      // like the robot app: one line, no control characters
      const t = raw.replace(/\s+/g, " ").replace(/[\u0000-\u001f\u007f]/g, "").trim();
      if (t.length > f.maxLength) return { error: `${f.label} can have at most ${f.maxLength} characters` };
      return { value: t };
    }
    case "list": {
      if (raw === null || raw === undefined) return f.optional ? { value: null } : { error: `${f.label} needs at least one entry` };
      const items = (Array.isArray(raw) ? raw : typeof raw === "string" ? raw.split(/[,;\s]+/) : null)?.map((x) =>
        typeof x === "string" ? x.trim().replace(/_/g, "-") : "",
      );
      if (!items) return { error: `${f.label} must be a list` };
      const list = [...new Set(items.filter(Boolean))];
      if (!list.length) return f.optional ? { value: null } : { error: `${f.label} needs at least one entry` };
      if (list.length > f.maxItems) return { error: `${f.label} can have at most ${f.maxItems} entries` };
      const re = new RegExp(f.pattern);
      const bad = list.find((x) => !re.test(x));
      if (bad) return { error: `${f.label}: "${bad.slice(0, 20)}" is not valid, use ${f.patternHelp}` };
      return { value: list };
    }
  }
}

// values that only make sense together, checked like the robot app does: speeds normal >= slow >= very slow,
// the shortest time between checks not longer than the longest. errors per "walk.key"
export function crossCheck(walk: Record<string, ConfigValue>): Record<string, string> {
  const errors: Record<string, string> = {};
  const n = (k: string) => (typeof walk[k] === "number" ? (walk[k] as number) : NaN);
  if (n("speedSlow") > n("speedNormal")) errors["walk.speedSlow"] = "The slow pace can't be faster than the normal pace";
  if (n("speedVerySlow") > n("speedSlow")) errors["walk.speedVerySlow"] = "The very slow pace can't be faster than the slow pace";
  if (n("checkMinS") > n("checkMaxS")) errors["walk.checkMaxS"] = "The longest time between checks can't be shorter than the shortest";
  return errors;
}

// the full settings: the defaults with the stored values that still pass the checks. returns what was dropped.
// fix: values that don't fit together go back to the defaults, as on the robot
export function mergeSettings(overrides: unknown, fix = true): { settings: RobotSettings; dropped: string[] } {
  const settings = defaultSettings();
  const dropped: string[] = [];
  const src = overrides && typeof overrides === "object" ? (overrides as Record<string, unknown>) : {};
  for (const [group, values] of Object.entries(src)) {
    if (group === "mode") {
      if (isModeValue(values)) settings.mode = values;
      else dropped.push("mode");
      continue;
    }
    if (!values || typeof values !== "object") continue;
    for (const [key, raw] of Object.entries(values as Record<string, unknown>)) {
      const f = findField(group, key);
      if (!f) {
        dropped.push(`${group}.${key}`);
        continue;
      }
      const r = checkValue(f, raw);
      if ("error" in r) dropped.push(`${group}.${key}`);
      else settings[f.group][f.key] = r.value;
    }
  }
  if (fix) {
    const d = defaultSettings().walk;
    const w = settings.walk;
    const bad = crossCheck(w);
    if (bad["walk.speedSlow"] || bad["walk.speedVerySlow"]) {
      for (const k of ["speedNormal", "speedSlow", "speedVerySlow"]) w[k] = d[k];
      dropped.push("walk.speeds");
    }
    if (bad["walk.checkMaxS"]) {
      w.checkMinS = d.checkMinS;
      w.checkMaxS = d.checkMaxS;
      dropped.push("walk.checks");
    }
  }
  return { settings, dropped };
}

// applies a change from the form to the stored overrides. change = { group: { key: value } }; reset = a group or "all".
// only values that differ from the default are kept. errors per "group.key"
export function applyChange(
  before: ConfigOverrides,
  change: unknown,
  reset?: unknown,
): { overrides: ConfigOverrides; errors: Record<string, string>; changed: boolean } {
  const next: ConfigOverrides = JSON.parse(JSON.stringify(before ?? {}));
  const errors: Record<string, string> = {};
  if (reset === "all") for (const g of CONFIG_GROUPS) delete next[g.key];
  else if (typeof reset === "string") {
    if (!CONFIG_GROUPS.some((g) => g.key === reset)) errors.reset = "reset must be a group or all";
    else delete next[reset as ConfigGroupKey];
  }
  const src = change && typeof change === "object" ? (change as Record<string, unknown>) : {};
  for (const [group, values] of Object.entries(src)) {
    if (group === "mode") {
      if (!isModeValue(values)) errors.mode = `mode must be one of ${MODES.join(", ")}`;
      else if (values === "normal") delete next.mode;
      else next.mode = values;
      continue;
    }
    if (!values || typeof values !== "object" || Array.isArray(values)) {
      errors[group] = `${group} must be a group of settings`;
      continue;
    }
    for (const [key, raw] of Object.entries(values as Record<string, unknown>)) {
      const f = findField(group, key);
      if (!f) {
        errors[`${group}.${key}`] = `there is no setting ${group}.${key}`;
        continue;
      }
      const r = checkValue(f, raw);
      if ("error" in r) {
        errors[`${group}.${key}`] = r.error;
        continue;
      }
      const g = (next[f.group] ??= {});
      if (same(r.value, f.default)) delete g[f.key];
      else g[f.key] = r.value;
      if (!Object.keys(g).length) delete next[f.group];
    }
  }
  Object.assign(errors, crossCheck(mergeSettings(next, false).settings.walk));
  return { overrides: next, errors, changed: JSON.stringify(next) !== JSON.stringify(before ?? {}) };
}
