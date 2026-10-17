import { NextResponse } from "next/server";
import { safeNext, tryLogin } from "@/lib/login";
import { isHttps, sessionCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

// the login form posts here (works without javascript). scripts can send json and get json back
export async function POST(req: Request) {
  const isJson = (req.headers.get("content-type") ?? "").includes("application/json");
  let password = "";
  let next = "/";
  if (isJson) {
    const body = (await req.json().catch(() => ({}))) as Record<string, unknown>;
    password = typeof body.password === "string" ? body.password : "";
    next = safeNext(body.next);
  } else {
    const form = await req.formData().catch(() => null);
    password = String(form?.get("password") ?? "");
    next = safeNext(form?.get("next"));
  }

  const result = await tryLogin(req, password);
  const back = (params: Record<string, string>) => {
    const url = new URL("/login", req.url);
    for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
    if (next !== "/") url.searchParams.set("next", next);
    return NextResponse.redirect(url, 303);
  };

  if (result.ok) {
    const res = isJson ? NextResponse.json({ ok: true, next }) : NextResponse.redirect(new URL(next, req.url), 303);
    res.headers.append("Set-Cookie", sessionCookie(result.session, isHttps(req)));
    return res;
  }
  if (result.reason === "off")
    return isJson ? NextResponse.json({ ok: true, next }) : NextResponse.redirect(new URL(next, req.url), 303);
  if (result.reason === "wait") {
    const minutes = String(result.minutes ?? 10);
    return isJson
      ? NextResponse.json(
          { error: `too many wrong passwords, try again in ${minutes} minutes` },
          { status: 429, headers: { "Retry-After": String(Number(minutes) * 60) } },
        )
      : back({ error: "wait", m: minutes });
  }
  return isJson ? NextResponse.json({ error: "wrong password" }, { status: 401 }) : back({ error: "wrong" });
}
