import { json, requireStaff, routeWith } from "@/lib/http";
import { retryLeg } from "@/lib/journeys";

export const dynamic = "force-dynamic";

// staff: nova gave up on the last walk (nobody came, lost the patient). same walk again
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  await requireStaff(req);
  return json(await retryLeg(id));
});
