import { json, readJson, routeWith } from "@/lib/http";
import { limit } from "@/lib/rate";
import { askFromPhone } from "@/lib/walk";
import { walkGuard } from "@/lib/walk-guard";

export const dynamic = "force-dynamic";

// public: { text } from the phone's speech-to-text (or typed) -> otto gets it as an ask command
export const POST = routeWith<{ code: string }>(async (req, { code }) => {
  const { journey, key } = await walkGuard(req, code, "act");
  await limit(`${key}|ask|${journey.id}`, 6, 60, "Otto needs a moment. Please try again in a minute.");
  return json(await askFromPhone(journey, (await readJson(req)).text), 201);
});
