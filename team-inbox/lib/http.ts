import { NextResponse } from "next/server";

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
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
export function robotAuthorized(req: Request) {
  const token = process.env.TEAM_INBOX_TOKEN;
  if (!token) return true;
  return req.headers.get("authorization") === `Bearer ${token}`;
}

export function requireRobot(req: Request) {
  if (!robotAuthorized(req)) throw new ApiError(401, "unauthorized");
}

// where links in teams cards point to. PUBLIC_BASE_URL wins, else the host the request came in on
export function baseUrl(req: Request) {
  const fixed = process.env.PUBLIC_BASE_URL?.trim();
  if (fixed) return fixed.replace(/\/+$/, "");
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host");
  const proto = req.headers.get("x-forwarded-proto") ?? new URL(req.url).protocol.replace(":", "");
  return host ? `${proto}://${host}` : new URL(req.url).origin;
}

function fail(err: unknown) {
  if (err instanceof ApiError) {
    return NextResponse.json({ error: err.message }, { status: err.status });
  }
  console.error(err);
  return NextResponse.json({ error: "server error" }, { status: 500 });
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

export function routeWithId(handler: (req: Request, id: string) => Promise<Response>) {
  return async (req: Request, ctx: { params: Promise<{ id: string }> }) => {
    try {
      const { id } = await ctx.params;
      return await handler(req, id);
    } catch (err) {
      return fail(err);
    }
  };
}

export function sinceParam(url: URL) {
  const raw = url.searchParams.get("since");
  if (!raw) return undefined;
  const t = Date.parse(raw);
  if (Number.isNaN(t)) throw new ApiError(400, "since must be an ISO date");
  return new Date(t).toISOString();
}

export function flag(url: URL, name: string) {
  const v = url.searchParams.get(name);
  return v === "1" || v === "true";
}
