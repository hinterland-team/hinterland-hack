import { ApiError, json, readJson, requireStaff, route, text } from "@/lib/http";
import { cancelLink, linkStatus, startLink } from "@/lib/telegram-link";

export const dynamic = "force-dynamic";

// staff, "Connect Telegram": { botToken } (or { channelId } of a telegram channel made before), isDefault?
// -> { code, bot, url, groupUrl, expiresAt, status: "waiting" }. the server waits for /start <code>
export const POST = route(async (req) => {
  await requireStaff(req);
  return json(await startLink(await readJson(req)), 201);
});

// ?code= -> waiting | connected (with the channel) | expired | error
export const GET = route(async (req) => {
  await requireStaff(req);
  const code = text(new URL(req.url).searchParams.get("code"), 80);
  if (!code) throw new ApiError(400, "code is required");
  return json(linkStatus(code));
});

// ?code= stops waiting
export const DELETE = route(async (req) => {
  await requireStaff(req);
  const code = text(new URL(req.url).searchParams.get("code"), 80);
  if (!code) throw new ApiError(400, "code is required");
  return json(cancelLink(code));
});
