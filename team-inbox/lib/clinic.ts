import { ApiError, text } from "./http";
import { getStore } from "./store";
import type { DoctorPatch } from "./store-types";
import {
  arrivalCard,
  helpCard,
  robotLabel,
  sendToTeams,
  unknownDoctorCard,
  webhookFor,
} from "./teams";
import {
  type CallCommand,
  type Doctor,
  type DoctorStats,
  type HelpRequest,
  type HelpUrgency,
  type RobotCommand,
  type Stats,
  type Visit,
  type VisitEvent,
  type VisitStatus,
  type VisitWithQueue,
  DEFAULT_CONSULT_MINUTES,
  MAX_CONSULT_MINUTES,
  MIN_CONSULT_MINUTES,
  WAITING_STATUSES,
  firstEventAt,
  isClosed,
  matchDoctor,
  normalizeName,
  queueFor,
  robotStatus,
} from "./types";

const now = () => new Date().toISOString();

function event(type: string, detail?: string): VisitEvent {
  return detail ? { at: now(), type, detail } : { at: now(), type };
}

export function robotName(value: unknown) {
  return (text(value, 40) ?? "nova").toLowerCase();
}

// where nova lives when it has nothing to do. offered as "Send to" on the board
export function homeSpot() {
  return process.env.ROBOT_HOME_SPOT?.trim() || "Reception Point";
}

// adds queuePosition and estimatedWaitMinutes, computed from the open visits right now
export async function withQueue(visits: Visit[]): Promise<VisitWithQueue[]> {
  const waiting = visits.some((v) => v.doctorId && WAITING_STATUSES.includes(v.status));
  if (!waiting) return visits.map((v) => ({ ...v, queuePosition: null, estimatedWaitMinutes: null }));
  const store = getStore();
  const [rows, doctors] = await Promise.all([store.listQueue(), store.listDoctors()]);
  const place = queueFor(rows, doctors);
  return visits.map((v) => ({ ...v, ...place(v) }));
}

export async function withQueueOne(visit: Visit) {
  return (await withQueue([visit]))[0];
}

export function callLink(base: string, visitId: string) {
  const key = process.env.CALL_KEY;
  return `${base}/api/visits/${visitId}/call${key ? `?k=${encodeURIComponent(key)}` : ""}`;
}

async function mustGetVisit(id: string) {
  const visit = await getStore().getVisit(id);
  if (!visit) throw new ApiError(404, "visit not found");
  return visit;
}

// sends the "patient arrived" card and moves the visit to notified
async function notifyDoctor(visit: Visit, doctor: Doctor, base: string) {
  const store = getStore();
  const url = webhookFor(doctor);
  if (!url) {
    const v = await store.addEvent(visit.id, event("teams_skipped", "No Teams channel set up, call in from the board"));
    return { visit: v ?? visit, notified: false };
  }
  const ok = await sendToTeams(url, arrivalCard(visit, doctor, callLink(base, visit.id)));
  if (!ok) {
    const v = await store.addEvent(visit.id, event("teams_failed", "Teams message could not be sent"));
    return { visit: v ?? visit, notified: false };
  }
  const sent = event("notified", `${doctor.name} got a Teams message`);
  const v =
    (await store.updateVisit(visit.id, { status: "notified", onlyIf: ["checked_in"] }, sent)) ??
    (await store.addEvent(visit.id, sent));
  return { visit: v ?? visit, notified: true };
}

export type CheckInResult = { visit: Visit; notified: boolean; doctorKnown: boolean; spot?: string };

export async function checkIn(body: Record<string, unknown>, base: string): Promise<CheckInResult> {
  const patientName = text(body.patientName, 120);
  if (!patientName) throw new ApiError(400, "patientName is required");
  const heard = text(body.doctor, 120);
  const robot = robotName(body.robot);

  const store = getStore();
  const doctors = (await store.listDoctors()).filter((d) => d.active);
  const doctor = matchDoctor(doctors, heard);

  const at = now();
  const visit = await store.createVisit(
    {
      id: crypto.randomUUID(),
      patientName,
      doctorId: doctor?.id ?? null,
      doctorName: doctor?.name ?? heard ?? "Not given",
      appointmentTime: text(body.appointmentTime, 20),
      language: text(body.language, 20),
      robot,
      status: "checked_in",
      createdAt: at,
      updatedAt: at,
    },
    { at, type: "checked_in", detail: `Checked in by ${robotLabel(robot)}` },
  );

  if (doctor) {
    const result = await notifyDoctor(visit, doctor, base);
    return { ...result, doctorKnown: true, spot: doctor.spot };
  }

  let v =
    (await store.addEvent(
      visit.id,
      event("doctor_unknown", heard ? `No doctor called "${heard}", reception will help` : "No doctor given, reception will help"),
    )) ?? visit;
  const notified = await sendToTeams(process.env.TEAMS_WEBHOOK_URL, unknownDoctorCard(v, heard ?? "nothing", `${base}/`));
  if (notified) v = (await store.addEvent(visit.id, event("reception_notified", "Reception got a Teams message"))) ?? v;
  return { visit: v, notified, doctorKnown: false };
}

export type CallResult = {
  state: "called" | "already" | "closed" | "no_doctor";
  visit: Visit;
  command?: CallCommand;
};

function newCommand(visit: Visit, doctor: Doctor): CallCommand {
  return {
    id: crypto.randomUUID(),
    robot: visit.robot,
    type: "call_patient",
    visitId: visit.id,
    patientName: visit.patientName,
    doctorName: doctor.name,
    spot: doctor.spot,
    createdAt: now(),
  };
}

// again: the robot gave up on the walk (visit back at "called"), staff sends it once more
export async function callPatient(id: string, via: "board" | "teams", again = false): Promise<CallResult> {
  const store = getStore();
  const visit = await mustGetVisit(id);
  if (isClosed(visit.status)) return { state: "closed", visit };
  if (again && visit.status === "called") {
    const doctor = visit.doctorId ? await store.getDoctor(visit.doctorId) : null;
    if (!doctor) return { state: "no_doctor", visit };
    for (let open = await store.openCommandForVisit(id); open; open = await store.openCommandForVisit(id)) {
      await store.finishCommand(open.id, now());
    }
    const updated = await store.addEvent(id, event("called_again", `${robotLabel(visit.robot)} sent again (board)`));
    const command = await store.createCommand(newCommand(visit, doctor));
    return { state: "called", visit: updated ?? visit, command };
  }
  if (!WAITING_STATUSES.includes(visit.status)) {
    return { state: "already", visit, command: (await store.openCommandForVisit(id)) ?? undefined };
  }
  const doctor = visit.doctorId ? await store.getDoctor(visit.doctorId) : null;
  if (!doctor) return { state: "no_doctor", visit };

  const updated = await store.updateVisit(
    id,
    { status: "called", onlyIf: WAITING_STATUSES },
    event("called", `${doctor.name} is ready (${via === "teams" ? "Teams" : "board"})`),
  );
  if (!updated) {
    const again = await mustGetVisit(id);
    return { state: isClosed(again.status) ? "closed" : "already", visit: again };
  }
  const command = await store.createCommand(newCommand(visit, doctor));
  return { state: "called", visit: updated, command };
}

function defaultDetail(status: VisitStatus, visit: Visit) {
  const robot = robotLabel(visit.robot);
  switch (status) {
    case "checked_in":
      return "Back to waiting";
    case "notified":
      return "Doctor notified";
    case "called":
      return `${visit.doctorName} is ready`;
    case "escorting":
      return `${robot} is walking the patient to ${visit.doctorName}`;
    case "with_doctor":
      return `With ${visit.doctorName}`;
    case "done":
      return "Visit done";
    case "cancelled":
      return "Visit cancelled";
  }
}

export async function setStatus(id: string, status: VisitStatus, detail?: string) {
  const store = getStore();
  const visit = await mustGetVisit(id);
  if (visit.status === status) {
    // same status: only worth a timeline line when the robot says why
    return detail ? ((await store.addEvent(id, event(status, detail))) ?? visit) : visit;
  }
  const updated = await store.updateVisit(id, { status }, event(status, detail ?? defaultDetail(status, visit)));
  if (!updated) throw new ApiError(404, "visit not found");
  if (isClosed(status)) await store.dropUndeliveredCommands(id, now());
  return updated;
}

export async function assignDoctor(id: string, doctorId: string, base: string) {
  const store = getStore();
  const visit = await mustGetVisit(id);
  if (isClosed(visit.status)) throw new ApiError(409, "visit is closed");
  const doctor = await store.getDoctor(doctorId);
  if (!doctor || !doctor.active) throw new ApiError(400, "unknown doctor");
  if (visit.doctorId === doctor.id) return visit;

  const updated = await store.updateVisit(
    id,
    { doctorId: doctor.id, doctorName: doctor.name },
    event("doctor_assigned", `Assigned to ${doctor.name}`),
  );
  if (!updated) throw new ApiError(404, "visit not found");
  if (updated.status === "checked_in") return (await notifyDoctor(updated, doctor, base)).visit;
  return updated;
}

// a poll also counts as seen for the online dot
export async function nextCommand(robot: string) {
  const store = getStore();
  const at = now();
  await store.touchRobot(robot, at);
  const command = await store.takeNextCommand(robot, at);
  if (command?.type === "call_patient") {
    await store.addEvent(command.visitId, event("robot_got_call", `${robotLabel(robot)} got the call`));
  }
  return command;
}

// works for every command type. only a call has a visit timeline to add to
export async function finishCommand(id: string) {
  const store = getStore();
  const at = now();
  const command = await store.finishCommand(id, at);
  if (!command) throw new ApiError(404, "command not found");
  if (command.doneAt === at && command.type === "call_patient") {
    await store.addEvent(command.visitId, event("robot_back", `${robotLabel(command.robot)} is back at reception`));
  }
  return command;
}

// staff on the board: say something out loud, or drive to a spot and stay there
export async function createRobotCommand(body: Record<string, unknown>): Promise<RobotCommand> {
  const id = crypto.randomUUID();
  const robot = robotName(body.robot);
  if (body.type === "say") {
    const say = text(body.text, 300);
    if (!say) throw new ApiError(400, "text is required");
    return getStore().createCommand({ id, robot, type: "say", text: say, createdAt: now() });
  }
  if (body.type === "go_to") {
    const spot = text(body.spot, 80);
    if (!spot) throw new ApiError(400, "spot is required");
    return getStore().createCommand({ id, robot, type: "go_to", spot, createdAt: now() });
  }
  throw new ApiError(400, "type must be say or go_to");
}

function numberOrNull(value: unknown) {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}

// the robot reports every 15 s. never refused for odd values: they are stored as null
export async function saveHeartbeat(body: Record<string, unknown>) {
  const battery = numberOrNull(body.battery);
  await getStore().saveHeartbeat({
    robot: robotName(body.robot),
    battery: battery === null ? null : Math.round(Math.min(100, Math.max(0, battery))),
    charging: body.charging === true,
    state: text(body.state, 40)?.toLowerCase() ?? null,
    position: text(body.position, 80) ?? null,
    goal: text(body.goal, 200) ?? null,
    detail: text(body.detail, 300) ?? null,
    at: now(),
  });
}

export async function getRobotStatus(robot: string) {
  const { lastSeenAt, heartbeat } = await getStore().getRobot(robot);
  return robotStatus(robot, lastSeenAt, heartbeat, Date.now());
}

export async function requestHelp(body: Record<string, unknown>, base: string) {
  const summary = text(body.summary, 500);
  if (!summary) throw new ApiError(400, "summary is required");
  const urgency: HelpUrgency = body.urgency === "urgent" ? "urgent" : "normal";
  const store = getStore();
  const help: HelpRequest = await store.createHelp({
    id: crypto.randomUUID(),
    robot: robotName(body.robot),
    summary,
    urgency,
    status: "open",
    notified: false,
    createdAt: now(),
  });
  const notified = await sendToTeams(process.env.TEAMS_WEBHOOK_URL, helpCard(help, `${base}/`));
  if (notified) await store.setHelpNotified(help.id, true);
  return { help: { ...help, notified }, notified };
}

function average(values: number[]) {
  return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
}

function waitSeconds(v: Visit) {
  const called = firstEventAt(v, "called");
  return called ? (Date.parse(called) - Date.parse(v.createdAt)) / 1000 : null;
}

export async function stats(since: string): Promise<Stats> {
  const store = getStore();
  const [visits, robots] = await Promise.all([store.listVisits({ since }), store.listRobots()]);
  const today = visits.filter((v) => v.createdAt >= since);
  const waits = today.map(waitSeconds).filter((w): w is number => w !== null);
  const avg = average(waits);

  const groups = new Map<string, Visit[]>();
  for (const v of visits) {
    const key = v.doctorId ?? "";
    groups.set(key, [...(groups.get(key) ?? []), v]);
  }
  const perDoctor: DoctorStats[] = [...groups.entries()].map(([doctorId, list]) => {
    const todays = list.filter((v) => v.createdAt >= since);
    const w = average(todays.map(waitSeconds).filter((x): x is number => x !== null));
    return {
      doctorId: doctorId || null,
      doctorName: doctorId ? list[0].doctorName : "Doctor not found",
      checkIns: todays.length,
      waiting: list.filter((v) => WAITING_STATUSES.includes(v.status)).length,
      withDoctor: list.filter((v) => v.status === "with_doctor").length,
      done: todays.filter((v) => v.status === "done").length,
      avgWaitSeconds: w === null ? null : Math.round(w),
    };
  });

  return {
    since,
    checkInsToday: today.length,
    avgWaitSeconds: avg === null ? null : Math.round(avg),
    avgWaitMinutes: avg === null ? null : Math.round((avg / 60) * 10) / 10,
    waitingNow: visits.filter((v) => WAITING_STATUSES.includes(v.status)).length,
    withDoctorNow: visits.filter((v) => v.status === "with_doctor").length,
    doneToday: today.filter((v) => v.status === "done").length,
    perDoctor,
    robots,
  };
}

function cleanWebhook(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  const url = text(value, 2000);
  if (!url) return null;
  if (!/^https?:\/\/\S+$/i.test(url)) throw new ApiError(400, "teamsWebhookUrl must be a http(s) link");
  return url;
}

// whole minutes 1-120. undefined = not sent
function consultMinutes(value: unknown) {
  if (value === undefined) return undefined;
  const n = numberOrNull(value);
  if (n === null || n < MIN_CONSULT_MINUTES || n > MAX_CONSULT_MINUTES) {
    throw new ApiError(400, `avgConsultMinutes must be a number from ${MIN_CONSULT_MINUTES} to ${MAX_CONSULT_MINUTES}`);
  }
  return Math.round(n);
}

async function ensureUniqueName(name: string, exceptId?: string) {
  const key = normalizeName(name);
  const clash = (await getStore().listDoctors()).find((d) => d.id !== exceptId && normalizeName(d.name) === key);
  if (clash) throw new ApiError(409, `there is already a doctor called ${clash.name}`);
}

export async function createDoctor(body: Record<string, unknown>) {
  const name = text(body.name, 80);
  if (!name || !normalizeName(name)) throw new ApiError(400, "name is required");
  await ensureUniqueName(name);
  const webhook = cleanWebhook(body.teamsWebhookUrl);
  const doctor: Doctor = {
    id: crypto.randomUUID(),
    name,
    spot: text(body.spot, 80) ?? name,
    active: body.active === undefined ? true : Boolean(body.active),
    avgConsultMinutes: consultMinutes(body.avgConsultMinutes) ?? DEFAULT_CONSULT_MINUTES,
    createdAt: now(),
  };
  if (webhook) doctor.teamsWebhookUrl = webhook;
  return getStore().createDoctor(doctor);
}

export async function updateDoctor(body: Record<string, unknown>) {
  const id = text(body.id, 80);
  if (!id) throw new ApiError(400, "id is required");
  const patch: DoctorPatch = {};
  if (body.name !== undefined) {
    const name = text(body.name, 80);
    if (!name || !normalizeName(name)) throw new ApiError(400, "name can't be empty");
    await ensureUniqueName(name, id);
    patch.name = name;
  }
  if (body.spot !== undefined) {
    const spot = text(body.spot, 80);
    if (!spot) throw new ApiError(400, "spot can't be empty");
    patch.spot = spot;
  }
  if (body.active !== undefined) patch.active = Boolean(body.active);
  const minutes = consultMinutes(body.avgConsultMinutes);
  if (minutes !== undefined) patch.avgConsultMinutes = minutes;
  const webhook = cleanWebhook(body.teamsWebhookUrl);
  if (webhook !== undefined) patch.teamsWebhookUrl = webhook;
  const doctor = await getStore().updateDoctor(id, patch);
  if (!doctor) throw new ApiError(404, "doctor not found");
  return doctor;
}
