import { ApiError, json, readJson, requestOrigin, requireStaff, route } from "@/lib/http";
import { appsView, callbackUrl, deleteApp, isProvider, saveApp } from "@/lib/oauth";

export const dynamic = "force-dynamic";

// staff: the saved slack / discord apps (client id, secret masked) and the redirect links to enter in those apps
export const GET = route(async (req) => {
  await requireStaff(req);
  const origin = requestOrigin(req);
  return json({
    ...(await appsView()),
    origin,
    https: origin.startsWith("https://"),
    redirect: { slack: callbackUrl(origin, "slack"), discord: callbackUrl(origin, "discord") },
  });
});

// { provider: "slack" | "discord", clientId, clientSecret } (an empty secret keeps the saved one)
export const PUT = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  if (!isProvider(body.provider)) throw new ApiError(400, "provider must be slack or discord");
  return json(await saveApp(body.provider, body));
});

// ?provider=slack
export const DELETE = route(async (req) => {
  await requireStaff(req);
  const p = new URL(req.url).searchParams.get("provider");
  if (!isProvider(p)) throw new ApiError(400, "provider must be slack or discord");
  return json(await deleteApp(p));
});
