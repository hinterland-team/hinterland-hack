import { isNotifyEvent } from "./channel-types";
import { withQueue } from "./journeys";
import { publicPlace } from "./places";
import { errorCount } from "./logs";
import { getFeed, getRobotStatus, listCommands, stats } from "./robot";
import { getStore } from "./store";
import { type JourneyWithQueue, currentLeg } from "./types";

// the walk lines the board shows per journey (the timeline shows 5), plus the messages of the current walk
// and the last failure / "with staff": that is all the reception and room pages read from the events
const BOARD_WALK_EVENTS = 8;
const ALWAYS = ["failed", "lost_patient", "with_staff"];

export function slimJourney(j: JourneyWithQueue): JourneyWithQueue {
  if (j.events.length <= BOARD_WALK_EVENTS + 4) return j;
  const since = currentLeg(j).createdAt;
  const keep = new Set<number>();
  const last = new Map<string, number>();
  let walk = 0;
  for (let i = j.events.length - 1; i >= 0; i--) {
    const e = j.events[i];
    if (ALWAYS.includes(e.type) && !last.has(e.type)) last.set(e.type, i);
    if (isNotifyEvent(e.type)) {
      if (e.at >= since) keep.add(i);
    } else if (walk < BOARD_WALK_EVENTS) {
      keep.add(i);
      walk++;
    }
  }
  for (const i of last.values()) keep.add(i);
  return { ...j, events: j.events.filter((_, i) => keep.has(i)) };
}

// everything the reception and room pages show, in one request (polled every 2 s, 304 when nothing changed)
export async function board(robot: string) {
  const store = getStore();
  const [open, places, help, status, feed, today, logErrors] = await Promise.all([
    store.listJourneys({ active: true }),
    store.listPlaces(),
    store.listHelp({ open: true }),
    getRobotStatus(robot),
    getFeed(robot, 40),
    stats(),
    errorCount(robot),
  ]);
  const commands = await listCommands(robot, 6, feed.showPatientText);
  const journeys = (await withQueue(open, open)).map(slimJourney);
  // the journey otto works on: the ticket on its screen, else the guide it fetched last
  const onScreen = status.screen?.ticket ? journeys.find((j) => j.ticket === status.screen?.ticket) : undefined;
  const fetched = commands.find((c) => c.type === "guide" && c.deliveredAt && !c.doneAt);
  const currentJourneyId = onScreen?.id ?? (fetched?.type === "guide" ? fetched.journeyId : undefined) ?? null;
  return {
    now: new Date().toISOString(),
    places: places.map(publicPlace),
    journeys,
    help,
    robot: status,
    commands,
    feed,
    stats: today,
    currentJourneyId,
    logErrors,
    teamsDefault: Boolean(process.env.TEAMS_WEBHOOK_URL),
  };
}

export type Board = Awaited<ReturnType<typeof board>>;
