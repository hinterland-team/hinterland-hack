// the live conversation feed. otto's lines and understood intents go to the store (db when set),
// so every server instance shows them, for at most 12 h. what a patient said word for word
// (only with the demo switch on) stays in this instance's memory (lib/feed.ts)
import { hoursAgo } from "./common";
import { FEED_HOURS, FEED_MAX, addFeed, clearFeedForJourney, listFeed } from "./feed";
import { getStore } from "./store";
import type { FeedItem } from "./types";

// ids that sort like they came in, so lines with the same time keep their order
let seq = 0;
export function feedId() {
  seq = (seq + 1) % 1_000_000;
  return `${Date.now().toString(36).padStart(9, "0")}-${String(seq).padStart(6, "0")}-${crypto.randomUUID().slice(0, 8)}`;
}

export const isRawPatientWords = (i: Pick<FeedItem, "who" | "kind">) => i.who === "patient" && i.kind === "said";

export async function saveFeed(robot: string, items: FeedItem[]) {
  const raw = items.filter(isRawPatientWords);
  const kept = items.filter((i) => !isRawPatientWords(i));
  if (raw.length) addFeed(raw);
  if (kept.length) await getStore().addFeedItems(robot, kept, FEED_MAX, hoursAgo(FEED_HOURS));
}

// newest first
export async function readFeed(robot: string, limit: number) {
  const stored = await getStore().listFeedItems(robot, limit, hoursAgo(FEED_HOURS));
  const memory = listFeed(robot, limit);
  return [...stored, ...memory]
    .sort((a, b) => b.at.localeCompare(a.at) || b.id.localeCompare(a.id))
    .slice(0, limit);
}

export async function clearJourneyFeed(journeyId: string) {
  clearFeedForJourney(journeyId);
  await getStore().deleteFeedForJourney(journeyId);
}
