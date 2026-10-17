import { json, requireStaff, routeWith } from "@/lib/http";
import { cancelJourney } from "@/lib/journeys";

export const dynamic = "force-dynamic";

// staff: cancelled, its open guide command is closed
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  await requireStaff(req);
  return json(await cancelJourney(id));
});
