import { createChannel, deleteChannel, notificationsView, updateChannel } from "@/lib/channels";
import { json, readJson, requireStaff, route, text } from "@/lib/http";

export const dynamic = "force-dynamic";

// staff only, also for reading: the channels, masked (never a token or a full webhook link), and who gets what
export const GET = route(async (req) => {
  await requireStaff(req);
  return json(await notificationsView());
});

// { type, label?, config: { ...fields of the type }, isDefault?, active? }
export const POST = route(async (req) => {
  await requireStaff(req);
  return json(await createChannel(await readJson(req)), 201);
});

// { id, label?, config?, isDefault?, active? }. an empty secret field keeps the saved one
export const PATCH = route(async (req) => {
  await requireStaff(req);
  return json(await updateChannel(await readJson(req)));
});

// ?id=<channelId> or { id }
export const DELETE = route(async (req) => {
  await requireStaff(req);
  const id = new URL(req.url).searchParams.get("id") ?? text((await readJson(req)).id, 200);
  return json(await deleteChannel(id ?? undefined));
});
