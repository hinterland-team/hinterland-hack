import { NextResponse, type NextRequest } from "next/server";
import { bearerValid, hasBearer, staffAuthorized } from "./lib/session";

// staff login when DASHBOARD_PASSWORD is set: session cookie from /login, or basic auth for scripts.
// robot routes check the bearer token themselves, teams links check CALL_KEY

const ROBOT_ROUTES: [string, RegExp][] = [
  ["GET", /^\/api\/robot\/next$/],
  ["POST", /^\/api\/robot\/heartbeat$/],
  ["POST", /^\/api\/robot\/commands\/[^/]+\/done$/],
  ["POST", /^\/api\/robot\/feed$/],
  ["POST", /^\/api\/robot\/logs$/],
  ["POST", /^\/api\/robot\/map$/],
  ["POST", /^\/api\/robot\/map\/image$/],
  ["GET", /^\/api\/robot\/config$/],
  ["POST", /^\/api\/help$/],
  // checks X-Api-Key = INTEGRATION_KEY itself
  ["POST", /^\/api\/integrations\/journeys$/],
];

// teams card buttons open these links. they carry CALL_KEY
const TEAMS_LINKS = [/^\/api\/journeys\/[^/]+\/(next|done)$/];

// health for the robot's server choice, and the patient's phone page (the walk code is the key, rate limited)
const OPEN_PATHS = [
  /^\/login$/,
  /^\/api\/login$/,
  /^\/api\/logout$/,
  /^\/api\/health$/,
  /^\/walk\/[^/]+$/,
  /^\/api\/walk\/[^/]+(\/ask)?$/,
];

export async function middleware(req: NextRequest) {
  const password = process.env.DASHBOARD_PASSWORD;
  if (!password) return NextResponse.next();

  const { pathname } = req.nextUrl;
  if (OPEN_PATHS.some((r) => r.test(pathname))) return NextResponse.next();
  if (ROBOT_ROUTES.some(([m, r]) => m === req.method && r.test(pathname))) return NextResponse.next();
  if (req.method === "GET" && TEAMS_LINKS.some((r) => r.test(pathname))) return NextResponse.next();

  // the robot may read dashboard routes with its token. the routes decide what it may change
  if (hasBearer(req) && bearerValid(req)) return NextResponse.next();
  if (await staffAuthorized(req)) return NextResponse.next();

  if (pathname.startsWith("/api/")) {
    return NextResponse.json({ error: "login needed" }, { status: 401 });
  }
  const login = new URL("/login", req.url);
  const next = pathname + req.nextUrl.search;
  if (next !== "/") login.searchParams.set("next", next);
  return NextResponse.redirect(login);
}

// default middleware runtime is enough: lib/session only uses web crypto.
// vercel.json pins the framework, without it vercel ran this file as plain routing middleware
export const config = {
  matcher: ["/((?!_next/static|_next/image|icon.svg|favicon.ico|otto-alerts-sw.js).*)"],
};
