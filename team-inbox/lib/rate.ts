import { ApiError } from "./http";
import { sha256 } from "./session";
import { getStore } from "./store";

// rate limits per address. the address is only kept as a hash

export async function ipKey(req: Request, scope: string) {
  const ip = req.headers.get("x-real-ip")?.trim() || req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "local";
  return `${scope}|${(await sha256(`${scope}|${ip}`)).slice(0, 32)}`;
}

function tooMany(windowSeconds: number, message: string) {
  return new ApiError(429, message, { "Retry-After": String(windowSeconds) });
}

const since = (windowSeconds: number) => new Date(Date.now() - windowSeconds * 1000).toISOString();

// counted in the store, so it holds across server instances
export async function overLimit(key: string, max: number, windowSeconds: number) {
  return (await getStore().countHits(key, since(windowSeconds))) >= max;
}

export async function addHit(key: string) {
  await getStore().addHit(key, new Date().toISOString());
}

// counts this call, then refuses when there were already max calls in the window
export async function limit(key: string, max: number, windowSeconds: number, message: string) {
  if (await overLimit(key, max, windowSeconds)) throw tooMany(windowSeconds, message);
  await addHit(key);
}

// in this instance's memory only: cheap enough for page polls
const g = globalThis as unknown as { __novaRate?: Map<string, number[]> };
const rateMap = (): Map<string, number[]> => (g.__novaRate ??= new Map<string, number[]>());

export function memoryLimit(key: string, max: number, windowSeconds: number, message: string) {
  const map = rateMap();
  const now = Date.now();
  const recent = (map.get(key) ?? []).filter((t: number) => t > now - windowSeconds * 1000);
  if (recent.length >= max) {
    map.set(key, recent);
    throw tooMany(windowSeconds, message);
  }
  recent.push(now);
  map.set(key, recent);
  if (map.size > 5000) {
    for (const [k, list] of map) if (!list.some((t: number) => t > now - windowSeconds * 1000)) map.delete(k);
  }
}
