import type { DoctorPatch, Store, VisitPatch, VisitQuery } from "./store-types";
import type { CallCommand, Doctor, HelpRequest, HelpStatus, RobotCommand, RobotHeartbeat, Visit, VisitEvent } from "./types";
import { DEFAULT_CONSULT_MINUTES, QUEUE_STATUSES, isClosed } from "./types";

type Data = {
  doctors: Doctor[];
  visits: Visit[];
  commands: RobotCommand[];
  help: HelpRequest[];
  robots: Record<string, string>;
  heartbeats: Record<string, RobotHeartbeat>;
};

// lives on globalThis so it survives hot reload. gone on restart
const g = globalThis as unknown as { __novaClinic?: Data };
const data: Data = (g.__novaClinic ??= { doctors: [], visits: [], commands: [], help: [], robots: {}, heartbeats: {} });
// a server that was already running keeps its older data object, so add what came later
data.heartbeats ??= {};

const copy = <T>(value: T): T => structuredClone(value);

// doctors added before avgConsultMinutes existed get the default
const outDoctor = (d: Doctor): Doctor => copy({ ...d, avgConsultMinutes: d.avgConsultMinutes ?? DEFAULT_CONSULT_MINUTES });

function matches(v: Visit, q: VisitQuery) {
  if (q.active && isClosed(v.status)) return false;
  if (q.since && v.createdAt < q.since && isClosed(v.status)) return false;
  return true;
}

export const memoryStore: Store = {
  kind: "memory",

  async listDoctors() {
    return [...data.doctors].sort((a, b) => a.name.localeCompare(b.name)).map(outDoctor);
  },
  async getDoctor(id) {
    const d = data.doctors.find((x) => x.id === id);
    return d ? outDoctor(d) : null;
  },
  async createDoctor(doctor) {
    data.doctors.push(copy(doctor));
    return outDoctor(doctor);
  },
  async updateDoctor(id, patch: DoctorPatch) {
    const d = data.doctors.find((x) => x.id === id);
    if (!d) return null;
    if (patch.name !== undefined) d.name = patch.name;
    if (patch.spot !== undefined) d.spot = patch.spot;
    if (patch.active !== undefined) d.active = patch.active;
    if (patch.avgConsultMinutes !== undefined) d.avgConsultMinutes = patch.avgConsultMinutes;
    if (patch.teamsWebhookUrl !== undefined) {
      if (patch.teamsWebhookUrl) d.teamsWebhookUrl = patch.teamsWebhookUrl;
      else delete d.teamsWebhookUrl;
    }
    return outDoctor(d);
  },

  async createVisit(visit, event) {
    const v: Visit = { ...copy(visit), events: [copy(event)] };
    data.visits.push(v);
    return copy(v);
  },
  async getVisit(id) {
    const v = data.visits.find((x) => x.id === id);
    return v ? copy(v) : null;
  },
  async listVisits(q) {
    return copy(
      data.visits
        .filter((v) => matches(v, q))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    );
  },
  async updateVisit(id, patch: VisitPatch, event: VisitEvent) {
    const v = data.visits.find((x) => x.id === id);
    if (!v) return null;
    if (patch.onlyIf && !patch.onlyIf.includes(v.status)) return null;
    if (patch.status) v.status = patch.status;
    if (patch.doctorId !== undefined) v.doctorId = patch.doctorId;
    if (patch.doctorName !== undefined) v.doctorName = patch.doctorName;
    v.updatedAt = event.at;
    v.events.push(copy(event));
    return copy(v);
  },
  async addEvent(id, event) {
    const v = data.visits.find((x) => x.id === id);
    if (!v) return null;
    v.events.push(copy(event));
    v.updatedAt = event.at;
    return copy(v);
  },
  async listQueue() {
    return data.visits
      .filter((v) => QUEUE_STATUSES.includes(v.status))
      .map(({ id, doctorId, status, createdAt }) => ({ id, doctorId, status, createdAt }));
  },

  async createCommand(command) {
    data.commands.push(copy(command));
    return copy(command);
  },
  async openCommandForVisit(visitId) {
    const c = data.commands.find(
      (x): x is CallCommand => x.type === "call_patient" && x.visitId === visitId && !x.doneAt,
    );
    return c ? copy(c) : null;
  },
  async takeNextCommand(robot, now) {
    const c = data.commands
      .filter((x) => x.robot === robot && !x.deliveredAt && !x.doneAt)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0];
    if (!c) return null;
    c.deliveredAt = now;
    return copy(c);
  },
  async finishCommand(id, now) {
    const c = data.commands.find((x) => x.id === id);
    if (!c) return null;
    c.doneAt ??= now;
    return copy(c);
  },
  async dropUndeliveredCommands(visitId, now) {
    for (const c of data.commands) {
      if (c.type === "call_patient" && c.visitId === visitId && !c.deliveredAt && !c.doneAt) c.doneAt = now;
    }
  },
  async listCommands(robot, limit) {
    // reversed first so commands from the same millisecond stay newest first after the stable sort
    return copy(
      data.commands
        .filter((c) => c.robot === robot)
        .reverse()
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .slice(0, limit),
    );
  },

  async touchRobot(name, now) {
    data.robots[name] = now;
  },
  async listRobots() {
    return Object.entries(data.robots)
      .map(([name, lastSeenAt]) => ({ name, lastSeenAt }))
      .sort((a, b) => b.lastSeenAt.localeCompare(a.lastSeenAt));
  },
  async saveHeartbeat(heartbeat) {
    data.heartbeats[heartbeat.robot] = copy(heartbeat);
    data.robots[heartbeat.robot] = heartbeat.at;
  },
  async getRobot(name) {
    const heartbeat = data.heartbeats[name];
    return { lastSeenAt: data.robots[name], heartbeat: heartbeat ? copy(heartbeat) : undefined };
  },

  async createHelp(help) {
    data.help.push(copy(help));
    return copy(help);
  },
  async listHelp(q) {
    return copy(
      data.help
        .filter((h) => !q.open || h.status === "open")
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    );
  },
  async updateHelp(id, status: HelpStatus, now) {
    const h = data.help.find((x) => x.id === id);
    if (!h) return null;
    h.status = status;
    if (status === "done") h.handledAt = now;
    else delete h.handledAt;
    return copy(h);
  },
  async setHelpNotified(id, notified) {
    const h = data.help.find((x) => x.id === id);
    if (h) h.notified = notified;
  },
};
