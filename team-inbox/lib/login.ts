import { getStore } from "./store";
import { makeSession, passwordMatches, sha256 } from "./session";

// 5 wrong passwords per address in 10 minutes, then a pause
export const LOGIN_MAX_FAILURES = 5;
export const LOGIN_WINDOW_MINUTES = 10;

export type LoginResult =
  { ok: true; session: string } | { ok: false; reason: "wrong" | "wait"; minutes?: number } | { ok: false; reason: "off" };

// the address is only kept as a hash
export async function clientKey(req: Request) {
  const ip = req.headers.get("x-real-ip")?.trim() || req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "local";
  return (await sha256(`login|${ip}`)).slice(0, 32);
}

export async function tryLogin(req: Request, given: string): Promise<LoginResult> {
  const password = process.env.DASHBOARD_PASSWORD;
  if (!password) return { ok: false, reason: "off" };
  const store = getStore();
  const key = await clientKey(req);
  const since = new Date(Date.now() - LOGIN_WINDOW_MINUTES * 60_000).toISOString();
  if ((await store.countLoginFailures(key, since)) >= LOGIN_MAX_FAILURES) {
    return { ok: false, reason: "wait", minutes: LOGIN_WINDOW_MINUTES };
  }
  if (given && (await passwordMatches(given, password))) {
    await store.clearLoginFailures(key);
    return { ok: true, session: await makeSession(password) };
  }
  await store.addLoginFailure(key, new Date().toISOString());
  // a small pause makes guessing slower
  await new Promise((r) => setTimeout(r, 400));
  return { ok: false, reason: "wrong" };
}

// only paths on this site, so the login can't send people elsewhere
export function safeNext(value: unknown) {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) return "/";
  if (value.startsWith("/login") || value.startsWith("/api/")) return "/";
  return value;
}
