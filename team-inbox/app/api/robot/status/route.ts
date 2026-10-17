import { NextResponse } from "next/server";
import { getRobotStatus, robotName } from "@/lib/clinic";
import { route } from "@/lib/http";

export const dynamic = "force-dynamic";

// dashboard: latest heartbeat plus online (heartbeat or poll in the last 45 s)
export const GET = route(async (req) => {
  const robot = robotName(new URL(req.url).searchParams.get("robot"));
  return NextResponse.json(await getRobotStatus(robot));
});
