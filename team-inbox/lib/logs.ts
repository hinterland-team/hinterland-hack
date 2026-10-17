import { hoursAgo, now, robotName } from "./common";
import { ApiError, text } from "./http";
import { cleanLogItems } from "./robot-data";
import { getStore } from "./store";
import { LOG_KEEP_HOURS, LOG_LEVELS, LOG_MAX_ROWS, LOG_SOURCES, type LogLevel, isOneOf, levelsFrom } from "./types";

export const LOG_PAGE_MAX = 1000;
export const LOG_PAGE_DEFAULT = 300;

// robot: { robot, items: [{ at, level, source, text }] }. kept 24 h, at most 5000 lines per robot
export async function postLogs(body: Record<string, unknown>) {
  const robot = robotName(body.robot);
  const cleaned = cleanLogItems(body.items, Date.now());
  if ("error" in cleaned) throw new ApiError(400, cleaned.error);
  const stored = await getStore().addLogs(robot, cleaned.items, now(), hoursAgo(LOG_KEEP_HOURS), LOG_MAX_ROWS);
  return { ok: true, stored, dropped: cleaned.dropped };
}

function cursor(value: string | null, name: string) {
  if (value === null || value === "") return undefined;
  const n = Number(value);
  if (!Number.isInteger(n) || n < 0) throw new ApiError(400, `${name} must be a line id`);
  return n;
}

// dashboard: ?robot=nova&level=warn (that level and up)&source=nav,voice&q=text&after=<id>|before=<id>&limit=300
export async function readLogs(url: URL) {
  const p = url.searchParams;
  const level = p.get("level");
  if (level && !isOneOf(LOG_LEVELS, level)) throw new ApiError(400, `level must be one of ${LOG_LEVELS.join(", ")}`);
  const sources = (p.get("source") ?? "")
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter((s) => isOneOf(LOG_SOURCES, s));
  const after = cursor(p.get("after"), "after");
  const before = cursor(p.get("before"), "before");
  if (after !== undefined && before !== undefined) throw new ApiError(400, "send after or before, not both");
  const limit = Math.min(LOG_PAGE_MAX, Math.max(1, Math.round(Number(p.get("limit"))) || LOG_PAGE_DEFAULT));
  const lines = await getStore().listLogs({
    robot: robotName(p.get("robot")),
    after,
    before,
    levels: level && level !== "debug" ? levelsFrom(level as LogLevel) : undefined,
    sources: sources.length ? sources : undefined,
    search: text(p.get("q"), 100),
    limit,
  });
  return { lines, more: lines.length === limit };
}

// for the robot card: errors in the last hour
export async function errorCount(robot: string) {
  return getStore().countLogs(robot, ["error"], hoursAgo(1));
}
