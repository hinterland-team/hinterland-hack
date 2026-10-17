// shared between server and browser - no imports here (tests load this file directly; type imports vanish)
import type { DemoOp, DemoScript, DemoState } from "./demo-script";

// ---------- places

export const PLACE_KINDS = ["room", "waiting", "reception", "exit", "other"] as const;
export type PlaceKind = (typeof PLACE_KINDS)[number];

// exit and reception can always be asked for by patients
export const ALWAYS_PUBLIC: PlaceKind[] = ["exit", "reception"];
// kinds that can have a waiting room and self check-in
export const ROOM_KINDS: PlaceKind[] = ["room", "other"];

export const DEFAULT_MINUTES = 10;
export const MIN_MINUTES = 1;
export const MAX_MINUTES = 120;

export type Place = {
  id: string;
  name: string;
  room?: string;
  // exact map spot name on the robot
  spot: string;
  kind: PlaceKind;
  public: boolean;
  // time with staff, for wait estimates
  avgMinutes: number;
  teamsWebhookUrl?: string;
  active: boolean;
  // a room's waiting room (a place of kind waiting)
  waitingPlaceId?: string;
  // shown to patients on the robot's check-in screen
  selfCheckIn: boolean;
  // a map spot otto tries once when the normal way is blocked
  viaSpot?: string;
  // second channel next to teams (needs RESEND_API_KEY + EMAIL_FROM)
  notifyEmail?: string;
  // the notification channels this place picked (/notifications). absent = the default channels
  channelIds?: string[];
  // the floor: the name of the robot map this place is on. absent = any floor
  mapName?: string;
  createdAt: string;
};

// ---------- journeys

export const PACES = ["normal", "slow", "very_slow"] as const;
export type Pace = (typeof PACES)[number];

export const JOURNEY_STATUSES = ["waiting_pickup", "walking", "arrived", "with_staff", "done", "cancelled"] as const;
export type JourneyStatus = (typeof JOURNEY_STATUSES)[number];

export const LEG_STATUSES = ["queued", "pickup", "walking", "arrived", "failed"] as const;
export type LegStatus = (typeof LEG_STATUSES)[number];

// what the robot may set on a leg with PATCH
export const ROBOT_LEG_STATUSES: LegStatus[] = ["pickup", "walking", "arrived", "failed"];
export const CLOSED_STATUSES: JourneyStatus[] = ["done", "cancelled"];
export const FINAL_LEG_STATUSES: LegStatus[] = ["arrived", "failed"];

export const EVENT_TYPES = [
  "created",
  "teams_notified",
  "teams_skipped",
  "teams_failed",
  "email_sent",
  "email_failed",
  // v3.3 channels: <type>_notified / <type>_failed (slack, telegram, whatsapp, discord, webhook; teams and email as above)
  "slack_notified",
  "slack_failed",
  "telegram_notified",
  "telegram_failed",
  "whatsapp_notified",
  "whatsapp_failed",
  "discord_notified",
  "discord_failed",
  "webhook_notified",
  "webhook_failed",
  "walk_control",
  "robot_assigned",
  "pickup_started",
  "patient_confirmed",
  "walking",
  "check_back",
  "slowed_down",
  "waiting_for_patient",
  "lost_patient",
  "arrived",
  "with_staff",
  "next_leg",
  "done",
  "cancelled",
  "failed",
  "feedback",
] as const;

export type JourneyEvent = { at: string; type: string; detail?: string };

export type Leg = {
  id: string;
  fromPlaceId: string | null;
  fromName?: string;
  fromSpot?: string;
  toPlaceId: string;
  toName: string;
  toSpot: string;
  toRoom?: string;
  status: LegStatus;
  createdAt: string;
  startedAt?: string;
  arrivedAt?: string;
};

export type Journey = {
  id: string;
  ticket: string;
  robot: string;
  pace: Pace;
  wheelchair: boolean;
  status: JourneyStatus;
  // the room the patient is here for, when Otto first walks them to its waiting room
  forPlaceId?: string;
  legs: Leg[];
  createdBy: "staff" | "robot";
  createdAt: string;
  updatedAt: string;
  endedAt?: string;
  events: JourneyEvent[];
  // 8 random characters for the patient's phone page (/walk/<code>). valid while the journey is open
  walkCode?: string;
};

export type QueueInfo = { queuePosition: number | null; estimatedWaitMinutes: number | null };
export type JourneyWithQueue = Journey & QueueInfo;

// ---------- robot commands

// ok / detail: otto's answer when it finished (POST /api/robot/commands/:id/done { ok, detail }): ok false = refused
// or failed ("Otto is in Show mode", "only on the current map"), detail = one short english line
type CommandBase = { id: string; robot: string; createdAt: string; deliveredAt?: string; doneAt?: string; ok?: boolean; detail?: string };
export type CommandResult = { ok: boolean; detail?: string };
export type GuideCommand = CommandBase & {
  type: "guide";
  journeyId: string;
  legId: string;
  ticket: string;
  pace: Pace;
  wheelchair: boolean;
  from: { name: string; spot: string };
  to: { name: string; spot: string; room?: string; viaSpot?: string };
  walkCode?: string;
  // absolute link of the phone page for the qr code. added on the way out, not stored
  walkUrl?: string;
};
export type SayCommand = CommandBase & { type: "say"; text: string };
// origin phone = the patient typed or said it on the walk page. its text is wiped once otto has it
export type AskCommand = CommandBase & { type: "ask"; text: string; journeyId?: string; origin?: "staff" | "phone" };
export type GoToCommand = CommandBase & { type: "go_to"; spot: string };
export const WALK_ACTIONS = ["wait", "resume", "slower", "faster"] as const;
export type WalkAction = (typeof WALK_ACTIONS)[number];
export type WalkControlCommand = CommandBase & {
  type: "walk_control";
  journeyId: string;
  action: WalkAction;
  origin?: "staff" | "phone";
};
// docs/robot-setup.md: change the floor map (spot = where otto stands on it, to localise), fetch the settings again,
// check which server answers
export type SwitchMapCommand = CommandBase & { type: "switch_map"; mapName: string; spot: string };
export type ConfigChangedCommand = CommandBase & { type: "config_changed" };
export type RecheckCommand = CommandBase & { type: "recheck" };
// normal / show / calm (lib/modes.ts), switched at the top of the dashboard
export type SetModeCommand = CommandBase & { type: "set_mode"; mode: "normal" | "show" | "calm" };
// demo mode (docs/demo-mode.md): start (+ script) / next / hold / resume / restart / stop / say (+ text)
export type DemoCommand = CommandBase & { type: "demo"; op: DemoOp; script?: DemoScript; text?: string };
export type RobotCommand =
  | GuideCommand
  | SayCommand
  | AskCommand
  | GoToCommand
  | WalkControlCommand
  | SwitchMapCommand
  | ConfigChangedCommand
  | RecheckCommand
  | SetModeCommand
  | DemoCommand;

// ---------- robot status

export const ROBOT_STATES = ["idle", "greeting", "talking", "fetching", "walking", "returning", "charging", "error"] as const;
export const SCREEN_VIEWS = ["idle", "checkin", "pickup", "walking", "check_back", "waiting", "arrived", "help"] as const;
export type ScreenView = (typeof SCREEN_VIEWS)[number];

// a heartbeat or a poll of /api/robot/next in this window means online
export const ROBOT_ONLINE_SECONDS = 45;

export type RobotScreen = { view: ScreenView; ticket?: string; title: string; step?: string; progress?: number };

export type RobotHeartbeat = {
  robot: string;
  battery: number | null;
  charging: boolean;
  state: string | null;
  position: string | null;
  goal: string | null;
  detail: string | null;
  screen: RobotScreen | null;
  // which server address the robot uses right now (it picks the first that answers /api/health)
  server: string | null;
  // the nova clinic app version on the robot
  version: string | null;
  // robot-setup.md: the floor map the robot is on, whether it knows where it stands on it, the settings version it runs
  mapName?: string | null;
  localised?: boolean | null;
  configVersion?: number | null;
  // the mode otto runs in now (normal / show / calm), and the one it takes when the walk under way is over
  mode?: string | null;
  modePending?: string | null;
  // demo mode: where otto is in the script (docs/demo-mode.md)
  demo?: DemoState | null;
  at: string;
};

export type RobotStatus = {
  robot: string;
  battery?: number | null;
  charging?: boolean;
  state?: string | null;
  position?: string | null;
  goal?: string | null;
  detail?: string | null;
  screen?: RobotScreen | null;
  server?: string | null;
  version?: string | null;
  mapName?: string | null;
  localised?: boolean | null;
  configVersion?: number | null;
  mode?: string | null;
  modePending?: string | null;
  demo?: DemoState | null;
  lastSeenAt?: string;
  online: boolean;
};

// ---------- live feed (memory only)

export const FEED_WHO = ["nova", "patient"] as const;
export const FEED_KINDS = ["said", "intent", "action"] as const;
export type FeedItem = {
  id: string;
  robot: string;
  journeyId?: string;
  at: string;
  who: (typeof FEED_WHO)[number];
  kind: (typeof FEED_KINDS)[number];
  text: string;
};

// ---------- robot logs (db, 24 h / 5000 rows per robot, never patient words)

export const LOG_LEVELS = ["debug", "info", "warn", "error"] as const;
export type LogLevel = (typeof LOG_LEVELS)[number];
export const LOG_SOURCES = ["app", "net", "nav", "voice", "brain", "vision", "lidar"] as const;
export type LogSource = (typeof LOG_SOURCES)[number];
export const LOG_KEEP_HOURS = 24;
export const LOG_MAX_ROWS = 5000;

export type LogLine = { id: number; robot: string; at: string; level: LogLevel; source: LogSource; text: string };

// "warn" -> ["warn", "error"]
export function levelsFrom(min: LogLevel): LogLevel[] {
  return LOG_LEVELS.slice(LOG_LEVELS.indexOf(min));
}

// ---------- live map (latest frame per robot + a short trail of poses)

export const MAP_SCAN_MAX = 240;
export const MAP_TRAIL_MAX = 120;
export const MAP_PLACES_MAX = 200;

export type MapPose = { x: number; y: number; theta: number };
export type MapPoint = { x: number; y: number };
export type MapSpot = { name: string; x: number; y: number };
// sim = the simulated patient of Virtual Otto (/simulate)
export type MapFollower = { x: number; y: number; source: "lidar" | "camera" | "sim" };
export type MapTrailPoint = { x: number; y: number; at: string };
// a floor map the robot has (robot-setup.md section 2)
export type MapInfo = { name: string; floor?: number };

export type MapState = {
  robot: string;
  mapName?: string;
  pose: MapPose;
  places: MapSpot[];
  scan: [number, number][];
  follower?: MapFollower;
  target?: MapSpot;
  remainingMeters?: number;
  trail: MapTrailPoint[];
  // the robot's floor maps, when it sent them
  maps?: MapInfo[];
  updatedAt: string;
};

export const MAP_MAPS_MAX = 20;

// the real floor plan of one map (robot-setup.md section 1). the png itself is served by /api/robot/map/image.
// map meters -> image pixels: u = (x - originX) / resolution, v = height - (y - originY) / resolution (y flipped)
export type MapImageMeta = {
  robot: string;
  mapName: string;
  width: number;
  height: number;
  resolution: number;
  originX: number;
  originY: number;
  hash: string;
  bytes: number;
  updatedAt: string;
};

// one floor for the floor selector: what the robot reported plus the places set up on it
export type FloorInfo = {
  name: string;
  floor?: number;
  current: boolean;
  image: boolean;
  // spots otto reported on this map (remembered from its frames), for "Otto is standing at"
  spots: string[];
};

// ---------- patient phone page

// no 0/O, 1/I/L: easy to read out and type
export const WALK_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const WALK_CODE_LENGTH = 8;
export const WALK_CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/;

// bytes from crypto.getRandomValues. 31^8 codes, rejection keeps every letter equally likely
export function walkCodeFrom(bytes: Uint8Array) {
  const n = WALK_CODE_ALPHABET.length;
  const limit = 256 - (256 % n);
  let out = "";
  for (const b of bytes) {
    if (b >= limit) continue;
    out += WALK_CODE_ALPHABET[b % n];
    if (out.length === WALK_CODE_LENGTH) return out;
  }
  return null;
}

export function normalizeWalkCode(value: unknown) {
  if (typeof value !== "string") return null;
  const code = value.trim().toUpperCase();
  return WALK_CODE_RE.test(code) ? code : null;
}

// ---------- help

export type HelpUrgency = "normal" | "urgent";
export type HelpStatus = "open" | "done";
export type HelpRequest = {
  id: string;
  robot: string;
  summary: string;
  urgency: HelpUrgency;
  status: HelpStatus;
  notified: boolean;
  journeyId?: string;
  ticket?: string;
  createdAt: string;
  handledAt?: string;
};

// ---------- stats (anonymous counters per day and place)

export const COUNTERS = [
  "journeys",
  "guides",
  "done",
  "cancelled",
  "walks",
  "walkSeconds",
  "lostPatients",
  "feedbackUp",
  "feedbackDown",
] as const;
export type Counter = (typeof COUNTERS)[number];
export type Counters = Record<Counter, number>;
export type StatsRow = Counters & { placeId: string };

export type PlaceStats = {
  placeId: string;
  name: string;
  guides: number;
  done: number;
  avgWalkMinutes: number | null;
  lostPatients: number;
};

export type Stats = {
  day: string;
  journeys: number;
  guides: number;
  done: number;
  cancelled: number;
  avgWalkMinutes: number | null;
  lostPatients: number;
  feedbackUp: number;
  feedbackDown: number;
  activeNow: number;
  perPlace: PlaceStats[];
};

export function emptyCounters(): Counters {
  return {
    journeys: 0,
    guides: 0,
    done: 0,
    cancelled: 0,
    walks: 0,
    walkSeconds: 0,
    lostPatients: 0,
    feedbackUp: 0,
    feedbackDown: 0,
  };
}

// ---------- small helpers

export function isOneOf<T extends string>(list: readonly T[], value: unknown): value is T {
  return typeof value === "string" && (list as readonly string[]).includes(value);
}

export function isClosed(status: JourneyStatus) {
  return CLOSED_STATUSES.includes(status);
}

export function currentLeg(j: Pick<Journey, "legs">): Leg {
  return j.legs[j.legs.length - 1];
}

// journey status follows the current leg. a failed leg waits for staff (send again or cancel)
export function statusForLeg(leg: LegStatus): JourneyStatus {
  if (leg === "walking") return "walking";
  if (leg === "arrived") return "arrived";
  return "waiting_pickup";
}

// "Dr. Blue", "dr blue", "DR-BLUE" -> "drblue"
export function normalizeName(value: string) {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]/gu, "");
}

function words(value: string) {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
}

// fuzzy score of a typed query against place texts. 0 = no match, higher = better.
// every query word has to hit one word (or a whole name without spaces, "drblue"):
// the same word > its start > inside it > its letters in order from its first letter
export function matchScore(query: string, texts: (string | undefined)[]) {
  const q = words(query);
  if (!q.length) return 1;
  const fields = texts.filter((t): t is string => Boolean(t));
  const hay = [...new Set([...fields.flatMap(words), ...fields.map(normalizeName).filter(Boolean)])];
  if (!hay.length) return 0;
  let score = 0;
  for (const part of q) {
    if (hay.some((w) => w === part)) score += 4;
    else if (hay.some((w) => w.startsWith(part))) score += 3;
    else if (hay.some((w) => w.includes(part))) score += 2;
    else if (hay.some((w) => w[0] === part[0] && subsequence(part, w))) score += 1;
    else return 0;
  }
  return score;
}

function subsequence(needle: string, hay: string) {
  let i = 0;
  for (const c of hay) if (c === needle[i] && ++i === needle.length) return true;
  return needle.length === 0;
}

// ---------- tickets: one letter + two digits, random among the free ones of today

// no I and O, they look like 1 and 0 on a screen
export const TICKET_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
export const TICKET_RE = /^[A-Z][0-9]{2}$/;

export function normalizeTicket(value: unknown) {
  if (typeof value !== "string") return null;
  const t = value.trim().toUpperCase().replace(/\s+/g, "");
  return TICKET_RE.test(t) ? t : null;
}

export function pickTicket(used: Iterable<string>, random: () => number = Math.random) {
  const taken = new Set(used);
  const free: string[] = [];
  for (const letter of TICKET_LETTERS) {
    for (let n = 10; n <= 99; n++) {
      const t = `${letter}${n}`;
      if (!taken.has(t)) free.push(t);
    }
  }
  if (!free.length) return null;
  return free[Math.min(free.length - 1, Math.floor(random() * free.length))];
}

// local calendar day, "2026-10-17". timeZone = CLINIC_TIMEZONE, undefined = the machine's zone
export function dayKey(date: Date | string | number, timeZone?: string) {
  const d = new Date(date);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timeZone || undefined,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(d);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

// ---------- waiting line

// the line a journey stands in: the room it is for, else where it goes
export function lineKey(j: Pick<Journey, "forPlaceId" | "legs">) {
  return j.forPlaceId ?? currentLeg(j).toPlaceId;
}

type LineEntry = { id: string; rank: number; at: string };

// place in line among arrived + walking journeys of the same line, and a rough wait:
// (position - 1) * avgMinutes, plus ceil(avgMinutes / 2) when someone is with staff there.
// order: called into the room itself first, then the ones waiting (arrival order), then the ones still walking
export function queueFor(open: Journey[], places: Pick<Place, "id" | "avgMinutes">[]) {
  const minutes = new Map(places.map((p) => [p.id, p.avgMinutes]));
  const lines = new Map<string, { entries: LineEntry[]; busy: boolean }>();
  for (const j of open) {
    const key = lineKey(j);
    const line = lines.get(key) ?? { entries: [], busy: false };
    const leg = currentLeg(j);
    if (j.status === "with_staff") line.busy = true;
    if (j.status === "arrived" || j.status === "walking") {
      const atTarget = leg.toPlaceId === key ? 0 : 2;
      const rank = atTarget + (j.status === "arrived" ? 0 : 1);
      line.entries.push({ id: j.id, rank, at: leg.arrivedAt ?? leg.startedAt ?? leg.createdAt });
    }
    lines.set(key, line);
  }
  for (const line of lines.values()) {
    line.entries.sort((a, b) => a.rank - b.rank || a.at.localeCompare(b.at) || a.id.localeCompare(b.id));
  }
  return (j: Journey): QueueInfo => {
    const key = lineKey(j);
    const per = minutes.get(key);
    const line = lines.get(key);
    const index = line?.entries.findIndex((e) => e.id === j.id) ?? -1;
    if (per === undefined || !line || index < 0) return { queuePosition: null, estimatedWaitMinutes: null };
    return { queuePosition: index + 1, estimatedWaitMinutes: index * per + (line.busy ? Math.ceil(per / 2) : 0) };
  };
}

// for a new patient: how many are already in line for a place, and a rough wait
export function placeWait(open: Journey[], place: Pick<Place, "id" | "avgMinutes">) {
  let ahead = 0;
  let busy = false;
  for (const j of open) {
    if (lineKey(j) !== place.id) continue;
    if (j.status === "with_staff") busy = true;
    else if (!isClosed(j.status) && currentLeg(j).status !== "failed") ahead++;
  }
  return { ahead, busy, minutes: ahead * place.avgMinutes + (busy ? Math.ceil(place.avgMinutes / 2) : 0) };
}

// ---------- robot

// lastSeenAt = newest of last poll and last heartbeat. now in ms
export function robotStatus(
  robot: string,
  lastSeenAt: string | undefined,
  beat: RobotHeartbeat | undefined,
  now: number,
): RobotStatus {
  const seen = [lastSeenAt, beat?.at]
    .filter((t): t is string => Boolean(t))
    .sort()
    .at(-1);
  if (!seen) return { robot, online: false };
  return {
    robot,
    battery: beat?.battery ?? null,
    charging: beat?.charging ?? false,
    state: beat?.state ?? null,
    position: beat?.position ?? null,
    goal: beat?.goal ?? null,
    detail: beat?.detail ?? null,
    screen: beat?.screen ?? null,
    server: beat?.server ?? null,
    version: beat?.version ?? null,
    mapName: beat?.mapName ?? null,
    localised: beat?.localised ?? null,
    configVersion: beat?.configVersion ?? null,
    mode: beat?.mode ?? null,
    modePending: beat?.modePending ?? null,
    demo: beat?.demo ?? null,
    lastSeenAt: seen,
    online: now - Date.parse(seen) <= ROBOT_ONLINE_SECONDS * 1000,
  };
}

// sums the per-place rows of one day into the stats answer
export function summarize(day: string, rows: StatsRow[], places: Pick<Place, "id" | "name">[], activeNow: number): Stats {
  const total = emptyCounters();
  for (const r of rows) for (const c of COUNTERS) total[c] += r[c];
  const names = new Map(places.map((p) => [p.id, p.name]));
  const avg = (walks: number, seconds: number) => (walks ? Math.round((seconds / walks / 60) * 10) / 10 : null);
  return {
    day,
    journeys: total.journeys,
    guides: total.guides,
    done: total.done,
    cancelled: total.cancelled,
    avgWalkMinutes: avg(total.walks, total.walkSeconds),
    lostPatients: total.lostPatients,
    feedbackUp: total.feedbackUp,
    feedbackDown: total.feedbackDown,
    activeNow,
    perPlace: rows
      .filter((r) => r.placeId)
      .map((r) => ({
        placeId: r.placeId,
        name: names.get(r.placeId) ?? "Removed place",
        guides: r.guides,
        done: r.done,
        avgWalkMinutes: avg(r.walks, r.walkSeconds),
        lostPatients: r.lostPatients,
      }))
      .sort((a, b) => b.guides - a.guides || a.name.localeCompare(b.name)),
  };
}
