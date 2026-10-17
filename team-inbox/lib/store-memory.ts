// in-memory store for local demos. only type imports here so tests can load it directly
import type { JourneyChange, NewJourney, PlacePatch, PurgeCutoffs, Store, StoredJourney } from "./store-types";
import type { Counters, HelpRequest, Place, RobotCommand, RobotHeartbeat, StatsRow } from "./types";

type Data = {
  places: Place[];
  journeys: (StoredJourney & { ticketDay: string })[];
  commands: RobotCommand[];
  help: HelpRequest[];
  robots: Record<string, string>;
  heartbeats: Record<string, RobotHeartbeat>;
  stats: Record<string, StatsRow & { day: string }>;
  settings: Record<string, string>;
  logins: { key: string; at: string }[];
};

const fresh = (): Data => ({
  places: [],
  journeys: [],
  commands: [],
  help: [],
  robots: {},
  heartbeats: {},
  stats: {},
  settings: {},
  logins: [],
});

// lives on globalThis so it survives hot reload. gone on restart.
// the v1/v2 data (with patient names) is dropped
const g = globalThis as unknown as { __novaClinic?: unknown; __novaClinicV3?: Data };
delete g.__novaClinic;
const data: Data = (g.__novaClinicV3 ??= fresh());

const copy = <T>(value: T): T => structuredClone(value);
const closed = (s: string) => s === "done" || s === "cancelled";

function outJourney(j: Data["journeys"][number]): StoredJourney {
  const out: StoredJourney & { ticketDay?: string } = copy(j);
  delete out.ticketDay;
  return out;
}

const KIND_ORDER = ["reception", "waiting", "room", "other", "exit"];

// for tests: start from nothing
export function resetMemoryStore() {
  Object.assign(data, fresh());
}

export const memoryStore: Store = {
  kind: "memory",

  async listPlaces() {
    return copy(
      [...data.places].sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind) || a.name.localeCompare(b.name)),
    );
  },
  async getPlace(id) {
    const p = data.places.find((x) => x.id === id);
    return p ? copy(p) : null;
  },
  async createPlace(place) {
    data.places.push(copy(place));
    return copy(place);
  },
  async updatePlace(id, patch: PlacePatch) {
    const p = data.places.find((x) => x.id === id);
    if (!p) return null;
    for (const key of ["name", "spot", "kind", "public", "avgMinutes", "active", "selfCheckIn"] as const) {
      if (patch[key] !== undefined) (p as Record<string, unknown>)[key] = patch[key];
    }
    for (const key of ["room", "teamsWebhookUrl", "waitingPlaceId"] as const) {
      if (patch[key] === undefined) continue;
      if (patch[key]) p[key] = patch[key] as string;
      else delete p[key];
    }
    return copy(p);
  },
  async deletePlace(id) {
    const before = data.places.length;
    data.places = data.places.filter((p) => p.id !== id);
    for (const p of data.places) if (p.waitingPlaceId === id) delete p.waitingPlaceId;
    return data.places.length < before;
  },

  async createJourney(j: NewJourney, leg, events) {
    if (data.journeys.some((x) => x.ticketDay === j.ticketDay && x.ticket === j.ticket)) return null;
    const stored = { ...copy(j), legs: [copy(leg)], events: copy(events), version: 1 };
    data.journeys.push(stored);
    return outJourney(stored);
  },
  async usedTickets(day) {
    return data.journeys.filter((j) => j.ticketDay === day).map((j) => j.ticket);
  },
  async getJourney(id) {
    const j = data.journeys.find((x) => x.id === id);
    return j ? outJourney(j) : null;
  },
  async findOpenByTicket(ticket) {
    const j = data.journeys
      .filter((x) => x.ticket === ticket && !closed(x.status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    return j ? outJourney(j) : null;
  },
  async listJourneys(q) {
    return data.journeys
      .filter((j) => !q.active || !closed(j.status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
      .map(outJourney);
  },
  async saveJourney(id, version, change: JourneyChange) {
    const j = data.journeys.find((x) => x.id === id);
    if (!j || j.version !== version) return null;
    if (change.leg) {
      const leg = j.legs.find((l) => l.id === change.leg!.id);
      if (leg) {
        if (change.leg.status) leg.status = change.leg.status;
        if (change.leg.startedAt) leg.startedAt ??= change.leg.startedAt;
        if (change.leg.arrivedAt) leg.arrivedAt ??= change.leg.arrivedAt;
      }
    }
    if (change.newLeg) j.legs.push(copy(change.newLeg));
    if (change.status) j.status = change.status;
    if (change.endedAt) j.endedAt = change.endedAt;
    if (change.forPlaceId === null) delete j.forPlaceId;
    else if (change.forPlaceId) j.forPlaceId = change.forPlaceId;
    j.events.push(...copy(change.events));
    j.updatedAt = change.updatedAt;
    j.version += 1;
    return outJourney(j);
  },

  async createCommand(command) {
    data.commands.push(copy(command));
    return copy(command);
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
  async closeJourneyCommands(journeyId, now, onlyUndelivered) {
    for (const c of data.commands) {
      if (c.type !== "guide" || c.journeyId !== journeyId || c.doneAt) continue;
      if (onlyUndelivered && c.deliveredAt) continue;
      c.doneAt = now;
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
    return copy(data.help.filter((h) => !q.open || h.status === "open").sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
  },
  async updateHelp(id, status, now) {
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

  async bumpStats(day, placeId, delta: Partial<Counters>) {
    const key = `${day}|${placeId}`;
    const row = (data.stats[key] ??= {
      day,
      placeId,
      journeys: 0,
      guides: 0,
      done: 0,
      cancelled: 0,
      walks: 0,
      walkSeconds: 0,
      lostPatients: 0,
      feedbackUp: 0,
      feedbackDown: 0,
    });
    for (const [k, v] of Object.entries(delta) as [keyof Counters, number | undefined][]) row[k] += v ?? 0;
  },
  async getStats(day) {
    return Object.values(data.stats)
      .filter((r) => r.day === day)
      .map((r) => {
        const row: StatsRow & { day?: string } = copy(r);
        delete row.day;
        return row;
      });
  },

  async getSetting(key) {
    return data.settings[key] ?? null;
  },
  async setSetting(key, value) {
    data.settings[key] = value;
  },

  async countLoginFailures(key, since) {
    return data.logins.filter((l) => l.key === key && l.at >= since).length;
  },
  async addLoginFailure(key, at) {
    data.logins.push({ key, at });
  },
  async clearLoginFailures(key) {
    data.logins = data.logins.filter((l) => l.key !== key);
  },

  async purge(c: PurgeCutoffs) {
    const gone = data.journeys.filter((j) => (j.endedAt && j.endedAt < c.ended) || j.updatedAt < c.stale).map((j) => j.id);
    if (gone.length) {
      const set = new Set(gone);
      data.journeys = data.journeys.filter((j) => !set.has(j.id));
      data.commands = data.commands.filter((x) => x.type !== "guide" || !set.has(x.journeyId));
    }
    data.commands = data.commands.filter((x) => x.type === "guide" || (x.doneAt ?? x.createdAt) >= c.ended);
    data.help = data.help.filter((h) => !(h.handledAt && h.handledAt < c.ended) && h.createdAt >= c.stale);
    data.logins = data.logins.filter((l) => l.at >= c.logins);
    return gone;
  },
};
