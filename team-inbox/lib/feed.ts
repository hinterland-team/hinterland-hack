// live conversation feed from the robot. memory only (never the db): last 200 items,
// cleared when the journey ends and after 12 h. only type imports, tests load it directly
import type { FeedItem } from "./types";

export const FEED_MAX = 200;
export const FEED_HOURS = 12;

const g = globalThis as unknown as { __ottoFeed?: FeedItem[] };
const feed = (): FeedItem[] => (g.__ottoFeed ??= []);

function trim(now: number) {
  const cutoff = new Date(now - FEED_HOURS * 3600_000).toISOString();
  const list = feed().filter((i) => i.at >= cutoff);
  g.__ottoFeed = list.slice(-FEED_MAX);
}

// items in time order (oldest first) are appended, the list keeps the newest 200
export function addFeed(items: FeedItem[], now = Date.now()) {
  feed().push(...items);
  feed().sort((a, b) => a.at.localeCompare(b.at));
  trim(now);
}

// newest first
export function listFeed(robot: string, limit = 50, now = Date.now()): FeedItem[] {
  trim(now);
  return feed()
    .filter((i) => i.robot === robot)
    .slice(-limit)
    .reverse()
    .map((i) => ({ ...i }));
}

export function clearFeedForJourney(journeyId: string) {
  g.__ottoFeed = feed().filter((i) => i.journeyId !== journeyId);
}

export function clearFeed() {
  g.__ottoFeed = [];
}
