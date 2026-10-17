import { withQueue } from "./journeys";
import { getFeed, getRobotStatus, listCommands, stats } from "./robot";
import { getStore } from "./store";

// everything the reception and room pages show, in one request (polled every 2 s)
export async function board(robot: string) {
  const store = getStore();
  const [open, places, help, status, commands, feed, today] = await Promise.all([
    store.listJourneys({ active: true }),
    store.listPlaces(),
    store.listHelp({ open: true }),
    getRobotStatus(robot),
    listCommands(robot, 6),
    getFeed(robot, 40),
    stats(),
  ]);
  const journeys = await withQueue(open, open);
  // the journey nova works on: the ticket on its screen, else the guide it fetched last
  const onScreen = status.screen?.ticket ? journeys.find((j) => j.ticket === status.screen?.ticket) : undefined;
  const fetched = commands.find((c) => c.type === "guide" && c.deliveredAt && !c.doneAt);
  const currentJourneyId = onScreen?.id ?? (fetched?.type === "guide" ? fetched.journeyId : undefined) ?? null;
  return {
    now: new Date().toISOString(),
    places,
    journeys,
    help,
    robot: status,
    commands,
    feed,
    stats: today,
    currentJourneyId,
    teamsDefault: Boolean(process.env.TEAMS_WEBHOOK_URL),
  };
}

export type Board = Awaited<ReturnType<typeof board>>;
