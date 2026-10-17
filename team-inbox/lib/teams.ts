// teams adaptive cards. only the ticket and places, never anything about the patient
import type { HelpRequest, Journey, Pace, Place } from "./types";

type CardElement = Record<string, unknown>;

// clock time for cards. CLINIC_TIMEZONE (e.g. Europe/Berlin) matters on servers that run in UTC
export function clock(isoTime: string) {
  return new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: process.env.CLINIC_TIMEZONE || undefined,
  }).format(new Date(isoTime));
}

export function robotLabel(robot: string) {
  return robot.charAt(0).toUpperCase() + robot.slice(1);
}

export const PACE_TEXT: Record<Pace, string> = { normal: "Normal", slow: "Slow", very_slow: "Very slow" };

export function placeLabel(place: Pick<Place, "name" | "room">) {
  return place.room ? `${place.name}, Room ${place.room}` : place.name;
}

function message(body: CardElement[], actions: CardElement[]) {
  return {
    type: "message",
    attachments: [
      {
        contentType: "application/vnd.microsoft.card.adaptive",
        contentUrl: null,
        content: {
          $schema: "http://adaptivecards.io/schemas/adaptive-card.json",
          type: "AdaptiveCard",
          version: "1.4",
          msteams: { width: "Full" },
          body,
          actions,
        },
      },
    ],
  };
}

function title(textValue: string, color?: string): CardElement {
  return { type: "TextBlock", text: textValue, weight: "Bolder", size: "Large", wrap: true, ...(color ? { color } : {}) };
}

function line(textValue: string): CardElement {
  return { type: "TextBlock", text: textValue, wrap: true, isSubtle: true, spacing: "Small" };
}

function facts(list: [string, string][]): CardElement {
  return { type: "FactSet", spacing: "Medium", facts: list.map(([t, value]) => ({ title: t, value })) };
}

function openUrl(titleText: string, url: string, style?: "positive" | "destructive"): CardElement {
  return { type: "Action.OpenUrl", title: titleText, url, ...(style ? { style } : {}) };
}

function paceText(j: Pick<Journey, "pace" | "wheelchair">) {
  return `${PACE_TEXT[j.pace]}${j.wheelchair ? ", wheelchair" : ""}`;
}

export type CardLinks = { exit?: string; done: string; callIn?: string };

// "A17 is on the way" to the place nova walks to
export function onTheWayCard(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  to: Pick<Place, "name" | "room">,
  expected: string,
  links: CardLinks,
) {
  const actions = [];
  if (links.exit) actions.push(openUrl("Done - guide to exit", links.exit, "positive"));
  actions.push(openUrl("Done - finished here", links.done));
  return message(
    [
      title(`${j.ticket} is on the way`),
      line("Nova is walking this patient to you. Nova only knows the ticket and the room."),
      facts([
        ["Ticket", j.ticket],
        ["To", placeLabel(to)],
        ["Pace", paceText(j)],
        ["Expected in", expected],
      ]),
    ],
    actions,
  );
}

// self check-in: the visitor sits in the room's waiting room, the doctor calls them in
export function visitorCard(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  room: Pick<Place, "name" | "room">,
  waiting: Pick<Place, "name" | "room">,
  links: CardLinks,
) {
  const actions = [];
  if (links.callIn) actions.push(openUrl("Call in", links.callIn, "positive"));
  actions.push(openUrl(links.exit ? "Done - guide to exit" : "Done - finished here", links.exit ?? links.done));
  return message(
    [
      title(`Your visitor has arrived - ticket ${j.ticket}, waiting in ${waiting.name}`),
      line("Tap Call in when you are ready. Nova picks the ticket up in the waiting room and walks them to you."),
      facts([
        ["Ticket", j.ticket],
        ["Waiting in", placeLabel(waiting)],
        ["For", placeLabel(room)],
        ["Pace", paceText(j)],
      ]),
    ],
    actions,
  );
}

export function helpCard(help: HelpRequest, boardUrl: string) {
  const urgent = help.urgency === "urgent";
  const list: [string, string][] = [["Robot", robotLabel(help.robot)]];
  if (help.ticket) list.push(["Ticket", help.ticket]);
  list.push(["Time", clock(help.createdAt)]);
  return message(
    [
      title(urgent ? "Urgent: help needed at reception" : "Help needed at reception", urgent ? "Attention" : undefined),
      { type: "TextBlock", text: help.summary, wrap: true, spacing: "Medium" },
      facts(list),
    ],
    [openUrl("Open reception", boardUrl)],
  );
}

export function webhookFor(place?: Pick<Place, "teamsWebhookUrl"> | null) {
  return place?.teamsWebhookUrl || process.env.TEAMS_WEBHOOK_URL || undefined;
}

// posts to a teams workflows webhook. false when nothing is set up or teams said no
export async function sendToTeams(url: string | undefined, payload: unknown): Promise<boolean> {
  if (!url) return false;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) console.error(`teams webhook answered ${res.status}`);
    return res.ok;
  } catch (err) {
    console.error("teams webhook failed", err instanceof Error ? err.message : err);
    return false;
  }
}
