import type {
  CallCommand,
  Doctor,
  HelpRequest,
  HelpStatus,
  QueueRow,
  RobotCommand,
  RobotHeartbeat,
  RobotSeen,
  Visit,
  VisitEvent,
  VisitStatus,
} from "./types";

export type DoctorPatch = Partial<Pick<Doctor, "name" | "spot" | "active" | "avgConsultMinutes">> & {
  teamsWebhookUrl?: string | null;
};

export type VisitPatch = {
  status?: VisitStatus;
  doctorId?: string | null;
  doctorName?: string;
  // only apply when the visit is in one of these states (null result otherwise)
  onlyIf?: VisitStatus[];
};

export type VisitQuery = {
  active?: boolean;
  // created since then, plus anything still open
  since?: string;
};

// last poll and last heartbeat of one robot. both missing = never seen
export type RobotRecord = { lastSeenAt?: string; heartbeat?: RobotHeartbeat };

export interface Store {
  kind: "postgres" | "memory";

  listDoctors(): Promise<Doctor[]>;
  getDoctor(id: string): Promise<Doctor | null>;
  createDoctor(doctor: Doctor): Promise<Doctor>;
  updateDoctor(id: string, patch: DoctorPatch): Promise<Doctor | null>;

  createVisit(visit: Omit<Visit, "events">, event: VisitEvent): Promise<Visit>;
  getVisit(id: string): Promise<Visit | null>;
  listVisits(query: VisitQuery): Promise<Visit[]>;
  updateVisit(id: string, patch: VisitPatch, event: VisitEvent): Promise<Visit | null>;
  addEvent(id: string, event: VisitEvent): Promise<Visit | null>;
  // waiting, escorting and with-doctor visits, for the waiting line
  listQueue(): Promise<QueueRow[]>;

  createCommand<C extends RobotCommand>(command: C): Promise<C>;
  openCommandForVisit(visitId: string): Promise<CallCommand | null>;
  takeNextCommand(robot: string, now: string): Promise<RobotCommand | null>;
  finishCommand(id: string, now: string): Promise<RobotCommand | null>;
  dropUndeliveredCommands(visitId: string, now: string): Promise<void>;
  // newest first
  listCommands(robot: string, limit: number): Promise<RobotCommand[]>;

  touchRobot(name: string, now: string): Promise<void>;
  listRobots(): Promise<RobotSeen[]>;
  // keeps only the latest heartbeat per robot. also counts as seen
  saveHeartbeat(heartbeat: RobotHeartbeat): Promise<void>;
  getRobot(name: string): Promise<RobotRecord>;

  createHelp(help: HelpRequest): Promise<HelpRequest>;
  listHelp(query: { open?: boolean }): Promise<HelpRequest[]>;
  updateHelp(id: string, status: HelpStatus, now: string): Promise<HelpRequest | null>;
  setHelpNotified(id: string, notified: boolean): Promise<void>;
}
