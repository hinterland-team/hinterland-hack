import { NextResponse } from "next/server";
import { finishCommand } from "@/lib/clinic";
import { requireRobot, routeWithId } from "@/lib/http";

export const dynamic = "force-dynamic";

// robot finished a command of any type (for a call: back at reception)
export const POST = routeWithId(async (req, id) => {
  requireRobot(req);
  const command = await finishCommand(id);
  return NextResponse.json({ ok: true, command });
});
