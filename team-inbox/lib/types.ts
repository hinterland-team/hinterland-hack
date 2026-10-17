// shared between server and browser - no server imports here

export const VISIT_STATUSES = [
  "checked_in",
  "notified",
  "called",
  "escorting",
  "with_doctor",
  "done",
  "cancelled",
] as const;
export type VisitStatus = (typeof VISIT_STATUSES)[number];

export const CLOSED_STATUSES: VisitStatus[] = ["done", "cancelled"];
export const WAITING_STATUSES: VisitStatus[] = ["checked_in", "notified"];
// the doctor is busy while someone is walked over or in the room
export const BUSY_STATUSES: VisitStatus[] = ["escorting", "with_doctor"];
// what the waiting line is computed from
export const QUEUE_STATUSES: VisitStatus[] = [...WAITING_STATUSES, ...BUSY_STATUSES];

export const DEFAULT_CONSULT_MINUTES = 10;
export const MIN_CONSULT_MINUTES = 1;
export const MAX_CONSULT_MINUTES = 120;

export type Doctor = {
  id: string;
  name: string;
  spot: string;
  teamsWebhookUrl?: string;
  active: boolean;
  // minutes per patient, for the waiting time estimate
  avgConsultMinutes: number;
  createdAt: string;
};

export type VisitEvent = {
  at: string;
  type: string;
  detail?: string;
};

export type Visit = {
  id: string;
  patientName: string;
  doctorId: string | null;
  doctorName: string;
  appointmentTime?: string;
  language?: string;
  robot: string;
  status: VisitStatus;
  createdAt: string;
  updatedAt: string;
  events: VisitEvent[];
};

// computed on read from the open visits, never stored
export type QueueInfo = { queuePosition: number | null; estimatedWaitMinutes: number | null };
export type VisitWithQueue = Visit & QueueInfo;
export type QueueRow = Pick<Visit, "id" | "doctorId" | "status" | "createdAt">;

type CommandBase = { id: string; robot: string; createdAt: string; deliveredAt?: string; doneAt?: string };
// doctor is ready: bring the patient to the doctor's spot
export type CallCommand = CommandBase & {
  type: "call_patient";
  visitId: string;
  patientName: string;
  doctorName: string;
  spot: string;
};
// staff typed it on the board, the robot says it out loud
export type SayCommand = CommandBase & { type: "say"; text: string };
// drive to the spot and stay there
export type GoToCommand = CommandBase & { type: "go_to"; spot: string };
export type RobotCommand = CallCommand | SayCommand | GoToCommand;

export const ROBOT_STATES = ["idle", "greeting", "talking", "fetching", "walking", "returning", "charging", "error"] as const;
export type RobotState = (typeof ROBOT_STATES)[number];

// a heartbeat or a poll of /api/robot/next in this window means online
export const ROBOT_ONLINE_SECONDS = 45;

// what the robot sent in its last heartbeat
export type RobotHeartbeat = {
  robot: string;
  battery: number | null;
  charging: boolean;
  state: string | null;
  position: string | null;
  goal: string | null;
  detail: string | null;
  at: string;
};

// GET /api/robot/status. a robot that was never seen only has robot and online
export type RobotStatus = {
  robot: string;
  battery?: number | null;
  charging?: boolean;
  state?: string | null;
  position?: string | null;
  goal?: string | null;
  detail?: string | null;
  lastSeenAt?: string;
  online: boolean;
};

export type HelpUrgency = "normal" | "urgent";
export type HelpStatus = "open" | "done";

export type HelpRequest = {
  id: string;
  robot: string;
  summary: string;
  urgency: HelpUrgency;
  status: HelpStatus;
  notified: boolean;
  createdAt: string;
  handledAt?: string;
};

export type RobotSeen = { name: string; lastSeenAt: string };

export type DoctorStats = {
  doctorId: string | null;
  doctorName: string;
  checkIns: number;
  waiting: number;
  withDoctor: number;
  done: number;
  avgWaitSeconds: number | null;
};

export type Stats = {
  since: string;
  checkInsToday: number;
  avgWaitSeconds: number | null;
  avgWaitMinutes: number | null;
  waitingNow: number;
  withDoctorNow: number;
  doneToday: number;
  perDoctor: DoctorStats[];
  robots: RobotSeen[];
};

export function isVisitStatus(value: unknown): value is VisitStatus {
  return typeof value === "string" && (VISIT_STATUSES as readonly string[]).includes(value);
}

export function isClosed(status: VisitStatus) {
  return CLOSED_STATUSES.includes(status);
}

// "Dr. Blue", "dr blue", "DR-BLUE" -> "drblue"
export function normalizeName(value: string) {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]/gu, "");
}

export function nameTokens(value: string) {
  return value
    .normalize("NFKD")
    .replace(/\p{M}/gu, "")
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean);
}

// exact match first, then the doctor with the most name parts (3+ chars) in what was heard. a tie means unknown
export function matchDoctor<T extends { name: string }>(doctors: T[], heard: string | undefined): T | null {
  if (!heard) return null;
  const key = normalizeName(heard);
  if (!key) return null;

  const exact = doctors.filter((d) => normalizeName(d.name) === key);
  if (exact.length === 1) return exact[0];
  if (exact.length > 1) return null;

  const heardTokens = new Set(nameTokens(heard));
  let best: T | null = null;
  let bestScore = 0;
  let tie = false;
  for (const d of doctors) {
    const parts = nameTokens(d.name).filter((t) => t.length >= 3);
    const score = parts.filter((t) => heardTokens.has(t)).length;
    if (score === 0) continue;
    if (score > bestScore) {
      best = d;
      bestScore = score;
      tie = false;
    } else if (score === bestScore) {
      tie = true;
    }
  }
  return tie ? null : best;
}

export function firstEventAt(visit: Visit, type: string) {
  return visit.events.find((e) => e.type === type)?.at;
}

function earlier(a: QueueRow, b: QueueRow) {
  return a.createdAt < b.createdAt || (a.createdAt === b.createdAt && a.id < b.id);
}

// place in the doctor's line (waiting visits, oldest first) and a rough wait:
// everyone ahead times the minutes per patient, plus half a consultation when the doctor is busy right now
export function queueFor(rows: QueueRow[], doctors: Pick<Doctor, "id" | "avgConsultMinutes">[]) {
  const lines = new Map<string, { waiting: QueueRow[]; busy: boolean }>();
  for (const r of rows) {
    if (!r.doctorId) continue;
    const line = lines.get(r.doctorId) ?? { waiting: [], busy: false };
    if (WAITING_STATUSES.includes(r.status)) line.waiting.push(r);
    if (BUSY_STATUSES.includes(r.status)) line.busy = true;
    lines.set(r.doctorId, line);
  }
  const perPatient = new Map(doctors.map((d) => [d.id, d.avgConsultMinutes]));

  return (visit: QueueRow): QueueInfo => {
    const minutes = visit.doctorId ? perPatient.get(visit.doctorId) : undefined;
    if (!visit.doctorId || minutes === undefined || !WAITING_STATUSES.includes(visit.status)) {
      return { queuePosition: null, estimatedWaitMinutes: null };
    }
    const line = lines.get(visit.doctorId);
    const ahead = (line?.waiting ?? []).filter((r) => r.id !== visit.id && earlier(r, visit)).length;
    return {
      queuePosition: ahead + 1,
      estimatedWaitMinutes: ahead * minutes + (line?.busy ? Math.ceil(minutes / 2) : 0),
    };
  };
}

// lastSeenAt = newest of last poll and last heartbeat. now in ms
export function robotStatus(
  robot: string,
  lastSeenAt: string | undefined,
  beat: RobotHeartbeat | undefined,
  now: number,
): RobotStatus {
  const seen = [lastSeenAt, beat?.at].filter((t): t is string => Boolean(t)).sort().at(-1);
  if (!seen) return { robot, online: false };
  return {
    robot,
    battery: beat?.battery ?? null,
    charging: beat?.charging ?? false,
    state: beat?.state ?? null,
    position: beat?.position ?? null,
    goal: beat?.goal ?? null,
    detail: beat?.detail ?? null,
    lastSeenAt: seen,
    online: now - Date.parse(seen) <= ROBOT_ONLINE_SECONDS * 1000,
  };
}
