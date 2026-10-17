import { callPageHtml } from "@/lib/call-page";
import { baseUrl, json, readJson, requireStaff, routeWith } from "@/lib/http";
import { nextLeg } from "@/lib/journeys";
import { placeLabel } from "@/lib/teams";
import { badLinkPage, linkKeyOk, linkPage } from "@/lib/teams-link";

export const dynamic = "force-dynamic";

// staff: { to: placeId } -> new leg from where the patient is now, a guide command and a teams card
export const POST = routeWith<{ id: string }>(async (req, { id }) => {
  await requireStaff(req);
  const body = await readJson(req);
  return json(await nextLeg(id, body.to, baseUrl(req)));
});

// teams button ("Call in", "Done - guide to exit"): same as POST, answers with a small page
export async function GET(req: Request, ctx: { params: Promise<{ id: string }> }) {
  if (!linkKeyOk(req)) return badLinkPage();
  const { id } = await ctx.params;
  return linkPage(async () => {
    const { journey, command } = await nextLeg(id, new URL(req.url).searchParams.get("to"), baseUrl(req), "teams");
    const to = placeLabel({ name: command.to.name, room: command.to.room });
    return callPageHtml({
      status: 200,
      pill: "On the way",
      heading: "Nova is on the way",
      lines: [`Nova picks up ticket ${journey.ticket} and walks to ${to}.`],
      note: "You can close this page.",
      moving: true,
    });
  });
}
