import { clearFeedForJourney } from "./feed";
import { text } from "./http";
import { purgeCutoffs } from "./retention";
import { getStore } from "./store";
import { type JourneyEvent, dayKey } from "./types";

export { RETENTION_HOURS, STALE_HOURS } from "./retention";

export const now = () => new Date().toISOString();

// today in the clinic's time zone (CLINIC_TIMEZONE), "2026-10-17"
export function today(at: string | number | Date = Date.now()) {
  return dayKey(at, process.env.CLINIC_TIMEZONE);
}

export function event(type: string, detail?: string, at = now()): JourneyEvent {
  return detail ? { at, type, detail } : { at, type };
}

export function robotName(value: unknown) {
  return (text(value, 40) ?? "nova").toLowerCase();
}

export function numberOrNull(value: unknown) {
  const n = typeof value === "number" ? value : typeof value === "string" && value.trim() ? Number(value) : NaN;
  return Number.isFinite(n) ? n : null;
}

export const hoursAgo = (hours: number, at = Date.now()) => new Date(at - hours * 3600_000).toISOString();

// runs on every write: journeys that ended 12 h ago go, with their commands, events and feed.
// log lines and map frames older than 24 h too
export async function retention(at = Date.now()) {
  const gone = await getStore().purge(purgeCutoffs(at));
  for (const id of gone) clearFeedForJourney(id);
  return gone;
}
