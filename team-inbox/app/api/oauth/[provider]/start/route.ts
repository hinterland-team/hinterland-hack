import { NextResponse } from "next/server";
import { ApiError, requestOrigin, requireStaff, routeWith } from "@/lib/http";
import { OAUTH_COOKIE, isProvider, startOAuth } from "@/lib/oauth";
import { isHttps } from "@/lib/session";

export const dynamic = "force-dynamic";

// staff, "Connect Slack" / "Connect Discord": off to the provider, with a one-time state in this browser's cookie.
// ?default=0 = the new channel is not a default channel
export const GET = routeWith<{ provider: string }>(async (req, { provider }) => {
  await requireStaff(req);
  if (!isProvider(provider)) throw new ApiError(404, "unknown provider");
  const isDefault = new URL(req.url).searchParams.get("default") !== "0";
  const { url, state } = await startOAuth(provider, requestOrigin(req), isDefault);
  const res = NextResponse.redirect(url, 302);
  res.headers.append(
    "Set-Cookie",
    [`${OAUTH_COOKIE}=${state}`, "Path=/api/oauth", "Max-Age=600", "HttpOnly", "SameSite=Lax", ...(isHttps(req) ? ["Secure"] : [])].join("; "),
  );
  return res;
});
