import { json } from "@/lib/http";
import { VERSION } from "@/lib/version";

export const dynamic = "force-dynamic";

// no login: the robot checks every 60 s which of its servers answers
export async function GET() {
  return json({ ok: true, version: VERSION, time: new Date().toISOString() });
}
