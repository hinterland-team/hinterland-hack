import { NextResponse } from "next/server";
import { assignDoctor, setStatus, withQueueOne } from "@/lib/clinic";
import { ApiError, baseUrl, readJson, routeWithId, text } from "@/lib/http";
import { getStore } from "@/lib/store";
import { isVisitStatus } from "@/lib/types";

export const dynamic = "force-dynamic";

export const GET = routeWithId(async (_req, id) => {
  const visit = await getStore().getVisit(id);
  if (!visit) throw new ApiError(404, "visit not found");
  return NextResponse.json(await withQueueOne(visit));
});

// { status?, detail?, doctorId? } - every change adds an event to the timeline
export const PATCH = routeWithId(async (req, id) => {
  const body = await readJson(req);
  const doctorId = text(body.doctorId, 80);
  if (body.status === undefined && !doctorId) throw new ApiError(400, "send status or doctorId");
  if (body.status !== undefined && !isVisitStatus(body.status)) {
    throw new ApiError(400, "status must be checked_in, notified, called, escorting, with_doctor, done or cancelled");
  }

  let visit = doctorId ? await assignDoctor(id, doctorId, baseUrl(req)) : null;
  if (isVisitStatus(body.status)) visit = await setStatus(id, body.status, text(body.detail, 200));
  return NextResponse.json(visit ? await withQueueOne(visit) : null);
});
