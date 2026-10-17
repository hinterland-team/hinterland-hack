import { NextResponse } from "next/server";
import { requestHelp } from "@/lib/clinic";
import { baseUrl, flag, readJson, requireRobot, route } from "@/lib/http";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// robot asks a human for help -> teams message to the practice channel
export const POST = route(async (req) => {
  requireRobot(req);
  const { help, notified } = await requestHelp(await readJson(req), baseUrl(req));
  return NextResponse.json({ ok: true, id: help.id, notified, help }, { status: 201 });
});

// dashboard: ?open=1 for the ones nobody took yet
export const GET = route(async (req) => {
  const help = await getStore().listHelp({ open: flag(new URL(req.url), "open") });
  return NextResponse.json(help);
});
