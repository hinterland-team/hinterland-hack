import { NextResponse } from "next/server";
import { bearerValid, hasBearer, staffAuthorized } from "./session";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public headers?: Record<string, string>,
  ) {
    super(message);
  }
}

export function text(value: unknown, max = 200): string | undefined {
  if (typeof value !== "string") return undefined;
  const t = value.trim().replace(/\s+/g, " ");
  return t ? t.slice(0, max) : undefined;
}

export async function readJson(req: Request): Promise<Record<string, unknown>> {
  const body = await req.json().catch(() => null);
  return body && typeof body === "object" && !Array.isArray(body) ? (body as Record<string, unknown>) : {};
}

// robot calls carry "Authorization: Bearer <TEAM_INBOX_TOKEN>" when the token is set
export function requireRobot(req: Request) {
  if (!bearerValid(req)) throw new ApiError(401, "unauthorized");
}

export type Caller = "staff" | "robot";

// staff = session cookie or basic auth (or an open dashboard without DASHBOARD_PASSWORD).
// robot = the bearer token. middleware already let the request in, this decides what it may do
export async function caller(req: Request): Promise<Caller> {
  if (process.env.DASHBOARD_PASSWORD && (await staffAuthorized(req))) return "staff";
  if (hasBearer(req)) {
    if (!bearerValid(req)) throw new ApiError(401, "unauthorized");
    return "robot";
  }
  if (!process.env.DASHBOARD_PASSWORD) return "staff";
  throw new ApiError(401, "login needed");
}

export async function requireStaff(req: Request) {
  if ((await caller(req)) !== "staff") throw new ApiError(403, "only staff can do this");
}

// where links in teams cards point to. PUBLIC_BASE_URL wins, else the host the request came in on
export function baseUrl(req: Request) {
  const fixed = process.env.PUBLIC_BASE_URL?.trim();
  if (fixed) return fixed.replace(/\/+$/, "");
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const proto = req.headers.get("x-forwarded-proto")?.split(",")[0] ?? new URL(req.url).protocol.replace(":", "");
  return host ? `${proto}://${host}` : new URL(req.url).origin;
}

function fail(err: unknown) {
  if (err instanceof ApiError) {
    return NextResponse.json({ error: err.message }, { status: err.status, headers: err.headers });
  }
  console.error(err);
  const hint = err instanceof Error && err.message.startsWith("database is not set up") ? err.message : "server error";
  return NextResponse.json({ error: hint }, { status: 500 });
}

// turns ApiError into json errors
export function route(handler: (req: Request) => Promise<Response>) {
  return async (req: Request) => {
    try {
      return await handler(req);
    } catch (err) {
      return fail(err);
    }
  };
}

export function routeWith<P extends Record<string, string>>(handler: (req: Request, params: P) => Promise<Response>) {
  return async (req: Request, ctx: { params: Promise<P> }) => {
    try {
      return await handler(req, await ctx.params);
    } catch (err) {
      return fail(err);
    }
  };
}

export function flag(url: URL, name: string) {
  const v = url.searchParams.get(name);
  return v === "1" || v === "true";
}

export function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { "Cache-Control": "no-store" } });
}
