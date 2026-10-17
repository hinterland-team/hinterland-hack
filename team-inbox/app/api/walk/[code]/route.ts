import { json, readJson, routeWith } from "@/lib/http";
import { walkGuard } from "@/lib/walk-guard";
import { controlWalk, walkView } from "@/lib/walk";
import { limit } from "@/lib/rate";

export const dynamic = "force-dynamic";

// public, no login: the patient's phone page. only the ticket, the destination and how far it is
export const GET = routeWith<{ code: string }>(async (req, { code }) => {
  const { journey } = await walkGuard(req, code, "read");
  return json(await walkView(journey));
});

// { action: wait | resume | slower | faster } -> walk_control command for nova
export const POST = routeWith<{ code: string }>(async (req, { code }) => {
  const { journey, key } = await walkGuard(req, code, "act");
  await limit(`${key}|act|${journey.id}`, 20, 60, "Too many taps. Please wait a moment.");
  const command = await controlWalk(journey, (await readJson(req)).action, "phone");
  return json({ ok: true, action: command.action }, 201);
});
