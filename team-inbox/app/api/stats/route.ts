import { NextResponse } from "next/server";
import { stats } from "@/lib/clinic";
import { route, sinceParam } from "@/lib/http";

export const dynamic = "force-dynamic";

// ?since=<iso of local midnight>. without it: midnight in the server's time zone
export const GET = route(async (req) => {
  let since = sinceParam(new URL(req.url));
  if (!since) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    since = d.toISOString();
  }
  return NextResponse.json(await stats(since));
});
