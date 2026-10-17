import { ApiError } from "./http";
import { addHit, ipKey, memoryLimit, overLimit } from "./rate";
import { journeyForCode } from "./walk";

// the phone page polls every 3 s, so reads have a generous limit (per server instance).
// unknown codes are counted in the store: 20 wrong ones in 10 minutes and the address waits
export const WALK_READS_PER_MINUTE = 90;
export const WALK_BAD_CODES = 20;
export const WALK_BAD_WINDOW_SECONDS = 600;

export async function walkGuard(req: Request, code: string, mode: "read" | "act") {
  const key = await ipKey(req, "walk");
  if (mode === "read") memoryLimit(`${key}|read`, WALK_READS_PER_MINUTE, 60, "Too many requests. Please wait a moment.");
  if (await overLimit(`${key}|bad`, WALK_BAD_CODES, WALK_BAD_WINDOW_SECONDS)) {
    throw new ApiError(429, "Too many wrong links. Please wait a few minutes.", { "Retry-After": String(WALK_BAD_WINDOW_SECONDS) });
  }
  try {
    return { journey: await journeyForCode(code), key };
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) await addHit(`${key}|bad`);
    throw err;
  }
}
