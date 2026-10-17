import { callPageHtml } from "@/lib/call-page";
import { json, requireStaff, routeWith } from "@/lib/http";
import { finishJourney } from "@/lib/journeys";
import { badLinkPage, linkKeyOk, linkPage } from "@/lib/teams-link";

export const dynamic = "force-dynamic";

// staff: the journey is done, otto is not needed any more
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  await requireStaff(req);
  return json(await finishJourney(id));
});

// teams button "Done - finished here"
export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!linkKeyOk(req)) return badLinkPage();
  const { id } = await ctx.params;
  return linkPage(async () => {
    const { journey, already } = await finishJourney(id, "teams");
    return callPageHtml({
      status: 200,
      pill: "Done",
      heading: already ? `Ticket ${journey.ticket} was already done` : `Ticket ${journey.ticket} is done`,
      lines: ["Thank you. Otto is free for the next patient."],
      note: "You can close this page.",
    });
  });
}
