import { NextResponse } from "next/server";
import { ApiError, readJson, routeWithId } from "@/lib/http";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// dashboard: { status: "done" } when someone took care of it
export const PATCH = routeWithId(async (req, id) => {
  const body = await readJson(req);
  if (body.status !== "open" && body.status !== "done") throw new ApiError(400, "status must be open or done");
  const help = await getStore().updateHelp(id, body.status, new Date().toISOString());
  if (!help) throw new ApiError(404, "help request not found");
  return NextResponse.json(help);
});
