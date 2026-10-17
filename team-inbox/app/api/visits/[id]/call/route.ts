import { NextResponse } from "next/server";
import { callPageHtml } from "@/lib/call-page";
import { callPatient, withQueueOne } from "@/lib/clinic";
import { ApiError, readJson, routeWithId } from "@/lib/http";
import { getStore } from "@/lib/store";
import { clock, robotLabel } from "@/lib/teams";

export const dynamic = "force-dynamic";

// dashboard: doctor is ready -> status called + robot command. { again: true } re-sends the robot after it gave up
export const POST = routeWithId(async (req, id) => {
  const body = await readJson(req);
  const result = await callPatient(id, "board", body.again === true);
  if (result.state === "closed") throw new ApiError(409, "visit is closed");
  if (result.state === "no_doctor") throw new ApiError(409, "pick a doctor for this visit first");
  return NextResponse.json({
    visit: await withQueueOne(result.visit),
    command: result.command ?? null,
    already: result.state === "already",
  });
});

// teams card button. cards can only open links, so this does the same as POST and shows a small page
export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  const { id } = await ctx.params;
  const key = process.env.CALL_KEY;
  if (key && new URL(req.url).searchParams.get("k") !== key) {
    return callPageHtml({ status: 403, heading: "This link does not work", lines: ["Please call the patient in from the reception board."] });
  }
  if (!(await getStore().getVisit(id))) {
    return callPageHtml({ status: 404, heading: "Visit not found", lines: ["It may have been removed from the reception board."] });
  }

  const { state, visit, command } = await callPatient(id, "teams");
  const robot = robotLabel(visit.robot);
  switch (state) {
    case "called":
      return callPageHtml({
        status: 200,
        pill: "Called in",
        heading: `${robot} is bringing ${visit.patientName} to you`,
        lines: [`${visit.doctorName}, called at ${clock(new Date().toISOString())}.`, `${robot} walks to ${command?.spot ?? visit.doctorName} now.`],
        note: "You can close this page.",
        moving: true,
      });
    case "already":
      return callPageHtml({
        status: 200,
        pill: visit.status === "with_doctor" ? "With you" : "On the way",
        heading:
          visit.status === "with_doctor"
            ? `${visit.patientName} is already with you`
            : `${robot} is already on the way with ${visit.patientName}`,
        lines: ["No need to tap again."],
        note: "You can close this page.",
        moving: visit.status !== "with_doctor",
      });
    case "closed":
      return callPageHtml({
        status: 409,
        pill: "Closed",
        heading: "This visit is closed",
        lines: [`${visit.patientName} was marked ${visit.status === "done" ? "done" : "cancelled"} on the reception board.`],
      });
    case "no_doctor":
      return callPageHtml({
        status: 409,
        pill: "Doctor missing",
        heading: "Pick a doctor first",
        lines: [`${robot} could not tell which doctor ${visit.patientName} is here for.`, "Pick the doctor on the reception board, then call again."],
      });
  }
}
