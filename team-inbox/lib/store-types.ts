import type {
  Counters,
  HelpRequest,
  HelpStatus,
  Journey,
  JourneyEvent,
  JourneyStatus,
  Leg,
  LegStatus,
  Place,
  RobotCommand,
  RobotHeartbeat,
  StatsRow,
} from "./types";

export type PlacePatch = Partial<Pick<Place, "name" | "spot" | "kind" | "public" | "avgMinutes" | "active" | "selfCheckIn">> & {
  // null removes it
  room?: string | null;
  teamsWebhookUrl?: string | null;
  waitingPlaceId?: string | null;
};

// a journey as stored. version guards against two writers at the same time
export type StoredJourney = Journey & { version: number };

export type JourneyChange = {
  updatedAt: string;
  status?: JourneyStatus;
  endedAt?: string;
  // null = the patient moved on from the room they came for
  forPlaceId?: string | null;
  leg?: { id: string; status?: LegStatus; startedAt?: string; arrivedAt?: string };
  newLeg?: Leg;
  events: JourneyEvent[];
};

export type NewJourney = Omit<Journey, "legs" | "events"> & { ticketDay: string };

export type RobotRecord = { lastSeenAt?: string; heartbeat?: RobotHeartbeat };

export type PurgeCutoffs = {
  // journeys ended before this, done help requests, old say/go_to/ask commands
  ended: string;
  // open journeys untouched since this (forgotten ones)
  stale: string;
  // login failures older than this
  logins: string;
};

export interface Store {
  kind: "postgres" | "memory";

  listPlaces(): Promise<Place[]>;
  getPlace(id: string): Promise<Place | null>;
  createPlace(place: Place): Promise<Place>;
  updatePlace(id: string, patch: PlacePatch): Promise<Place | null>;
  // also clears waitingPlaceId on rooms that pointed to it
  deletePlace(id: string): Promise<boolean>;

  // null when the ticket is already taken that day
  createJourney(journey: NewJourney, leg: Leg, events: JourneyEvent[]): Promise<StoredJourney | null>;
  usedTickets(day: string): Promise<string[]>;
  getJourney(id: string): Promise<StoredJourney | null>;
  // open journeys with that ticket, newest first
  findOpenByTicket(ticket: string): Promise<StoredJourney | null>;
  // newest first
  listJourneys(query: { active?: boolean }): Promise<StoredJourney[]>;
  // only applies when the stored version still matches. null = changed meanwhile (or gone)
  saveJourney(id: string, version: number, change: JourneyChange): Promise<StoredJourney | null>;

  createCommand<C extends RobotCommand>(command: C): Promise<C>;
  takeNextCommand(robot: string, now: string): Promise<RobotCommand | null>;
  finishCommand(id: string, now: string): Promise<RobotCommand | null>;
  // closes open commands of a journey. onlyUndelivered = leave the one the robot already has
  closeJourneyCommands(journeyId: string, now: string, onlyUndelivered: boolean): Promise<void>;
  // newest first
  listCommands(robot: string, limit: number): Promise<RobotCommand[]>;

  touchRobot(name: string, now: string): Promise<void>;
  // keeps only the latest heartbeat per robot. also counts as seen
  saveHeartbeat(heartbeat: RobotHeartbeat): Promise<void>;
  getRobot(name: string): Promise<RobotRecord>;

  createHelp(help: HelpRequest): Promise<HelpRequest>;
  listHelp(query: { open?: boolean }): Promise<HelpRequest[]>;
  updateHelp(id: string, status: HelpStatus, now: string): Promise<HelpRequest | null>;
  setHelpNotified(id: string, notified: boolean): Promise<void>;

  bumpStats(day: string, placeId: string, delta: Partial<Counters>): Promise<void>;
  getStats(day: string): Promise<StatsRow[]>;

  getSetting(key: string): Promise<string | null>;
  setSetting(key: string, value: string): Promise<void>;

  // login failures per hashed address, for the rate limit
  countLoginFailures(key: string, since: string): Promise<number>;
  addLoginFailure(key: string, at: string): Promise<void>;
  clearLoginFailures(key: string): Promise<void>;

  // retention. returns the ids of the journeys that were deleted
  purge(cutoffs: PurgeCutoffs): Promise<string[]>;
}
