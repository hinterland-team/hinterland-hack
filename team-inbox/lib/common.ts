import { clearFeedForJourney } from "./feed";
import { text } from "./http";
import { getStore } from "./store";
import { type JourneyEvent, dayKey } from "./types";

export const RETENTION_HOURS = 12;
// open journeys nobody touched for a day are treated as ended
export const STALE_HOURS = 24;

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

// runs on every write: journeys that ended 12 h ago go, with their commands, events and feed
export async function retention(at = Date.now()) {
  const gone = await getStore().purge({
    ended: new Date(at - RETENTION_HOURS * 3600_000).toISOString(),
    stale: new Date(at - STALE_HOURS * 3600_000).toISOString(),
    logins: new Date(at - 3600_000).toISOString(),
  });
  for (const id of gone) clearFeedForJourney(id);
  return gone;
}
