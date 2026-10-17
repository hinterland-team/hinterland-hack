import { NextResponse, type NextRequest } from "next/server";

// optional basic auth for the dashboard (DASHBOARD_PASSWORD, any user name).
// robot routes check the bearer token themselves, teams call links check CALL_KEY

function same(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// robot routes skip basic auth and check the bearer themselves. the iot hook checks its own key.
// /api/robot/status and /api/robot/commands are dashboard routes
const ROBOT_ROUTES = [/^\/api\/robot\/next$/, /^\/api\/robot\/heartbeat$/, /^\/api\/robot\/commands\/[^/]+\/done$/];

function robotOnly(method: string, path: string) {
  if (path.startsWith("/api/iot/hook/") || ROBOT_ROUTES.some((r) => r.test(path))) return true;
  return method === "POST" && (path === "/api/visits" || path === "/api/help");
}

function callLink(method: string, path: string) {
  return method === "GET" && /^\/api\/visits\/[^/]+\/call$/.test(path);
}

function basicPassword(header: string) {
  if (!header.startsWith("Basic ")) return null;
  try {
    const decoded = atob(header.slice(6));
    return decoded.slice(decoded.indexOf(":") + 1);
  } catch {
    return null;
  }
}

export function middleware(req: NextRequest) {
  const password = process.env.DASHBOARD_PASSWORD;
  if (!password) return NextResponse.next();

  const { pathname } = req.nextUrl;
  if (robotOnly(req.method, pathname) || callLink(req.method, pathname)) return NextResponse.next();

  const header = req.headers.get("authorization") ?? "";
  const token = process.env.TEAM_INBOX_TOKEN;
  if (token && same(header, `Bearer ${token}`)) return NextResponse.next();

  const given = basicPassword(header);
  if (given !== null && same(given, password)) return NextResponse.next();

  return new NextResponse("Login needed", {
    status: 401,
    headers: { "WWW-Authenticate": 'Basic realm="Nova Clinic", charset="UTF-8"' },
  });
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|icon.svg|favicon.ico).*)"],
};
