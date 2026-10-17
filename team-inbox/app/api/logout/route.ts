import { NextResponse } from "next/server";
import { isHttps, sessionCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

// sign out: the cookie is cleared, back to the login page
export async function POST(req: Request) {
  const res = NextResponse.redirect(new URL("/login?out=1", req.url), 303);
  res.headers.append("Set-Cookie", sessionCookie("", isHttps(req), 0));
  return res;
}
