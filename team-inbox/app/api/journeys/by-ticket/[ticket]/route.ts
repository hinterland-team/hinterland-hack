import { caller, json, routeWith } from "@/lib/http";
import { journeyByTicket } from "@/lib/journeys";

export const dynamic = "force-dynamic";

// robot (WAITING_STATUS): the open journey with that ticket, 404 when there is none
export const GET = routeWith<{ ticket: string }>(async (req, { ticket }) => {
  await caller(req);
  return json(await journeyByTicket(decodeURIComponent(ticket)));
});
