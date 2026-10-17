// how long things are kept. no imports, so the store, common.ts and tests can all use it
import type { PurgeCutoffs } from "./store-types";

export const RETENTION_HOURS = 12;
// open journeys nobody touched for a day are treated as ended
export const STALE_HOURS = 24;
export const LOGIN_HOURS = 1;
export const LOG_HOURS = 24;

const ago = (hours: number, at: number) => new Date(at - hours * 3600_000).toISOString();

export function purgeCutoffs(at = Date.now()): PurgeCutoffs {
  return { ended: ago(RETENTION_HOURS, at), stale: ago(STALE_HOURS, at), logins: ago(LOGIN_HOURS, at), logs: ago(LOG_HOURS, at) };
}
