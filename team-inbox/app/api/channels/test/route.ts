import { testChannel } from "@/lib/channels";
import { baseUrl, json, readJson, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// { id } -> sends a test message to that channel: { ok, text, at }
export const POST = route(async (req) => {
  await requireStaff(req);
  return json(await testChannel((await readJson(req)).id, baseUrl(req)));
});
