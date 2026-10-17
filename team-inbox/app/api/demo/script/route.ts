import { readScript, resetScript, saveScript } from "@/lib/demo";
import { json, readJson, requireStaff, route } from "@/lib/http";

export const dynamic = "force-dynamic";

// staff, Settings -> Demo script: { script, isDefault, updatedAt } (the default of docs/demo-mode.md until saved)
export const GET = route(async (req) => {
  await requireStaff(req);
  return json(await readScript());
});

// { script } -> saved. a wrong script -> 400 with the first problem
export const PUT = route(async (req) => {
  await requireStaff(req);
  const body = await readJson(req);
  return json(await saveScript(body.script));
});

// back to the default script
export const DELETE = route(async (req) => {
  await requireStaff(req);
  return json(await resetScript());
});
