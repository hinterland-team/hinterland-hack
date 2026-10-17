import { NextResponse } from "next/server";
import { ApiError, requestOrigin, requireStaff } from "@/lib/http";
import { OAUTH_COOKIE, finishOAuth, isProvider } from "@/lib/oauth";
import { cookieValue, isHttps } from "@/lib/session";

export const dynamic = "force-dynamic";

// slack / discord send the browser back here with ?code&state (or ?error). the channel is added, then back to
// Settings -> Notifications with a short note. never shows the webhook link
export async function GET(req: Request, ctx: { params: Promise<{ provider: string }> }) {
  const { provider } = await ctx.params;
  const back = new URL("/settings/notifications", requestOrigin(req));
  try {
    await requireStaff(req);
    if (!isProvider(provider)) throw new ApiError(404, "unknown provider");
    const channel = await finishOAuth(provider, new URL(req.url).searchParams, cookieValue(req.headers.get("cookie"), OAUTH_COOKIE));
    back.searchParams.set("connected", channel.id);
  } catch (err) {
    back.searchParams.set("oauth_error", err instanceof ApiError ? err.message : "That did not work, please try again");
    if (!(err instanceof ApiError)) console.error("oauth callback failed", err instanceof Error ? err.message : "");
  }
  const res = NextResponse.redirect(back, 303);
  res.headers.append(
    "Set-Cookie",
    [`${OAUTH_COOKIE}=`, "Path=/api/oauth", "Max-Age=0", "HttpOnly", "SameSite=Lax", ...(isHttps(req) ? ["Secure"] : [])].join("; "),
  );
  return res;
}
