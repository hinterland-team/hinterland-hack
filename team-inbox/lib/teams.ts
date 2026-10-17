// teams adaptive cards. only the ticket and places, never anything about the patient
import { type CardLinks, type NotifyMessage, helpMessage, onTheWayMessage, visitorMessage } from "./messages";
import type { HelpRequest, Journey, Place } from "./types";

export { type CardLinks, PACE_TEXT, clock, placeLabel, robotLabel } from "./messages";

type CardElement = Record<string, unknown>;

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

// any message as an adaptive card 1.4: title, one line, the facts and a link button per link
export function teamsCard(m: NotifyMessage) {
  return message(
    [
      title(m.title, m.urgent ? "Attention" : undefined),
      m.textStrong ? { type: "TextBlock", text: m.text, wrap: true, spacing: "Medium" } : line(m.text),
      facts(m.facts),
    ],
    m.links.map((l) => openUrl(l.label, l.url, l.primary ? "positive" : undefined)),
  );
}

// "A17 is on the way" to the place otto walks to
export function onTheWayCard(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  to: Pick<Place, "name" | "room">,
  expected: string,
  links: CardLinks,
) {
  return teamsCard(onTheWayMessage(j, to, expected, links));
}

// self check-in: the visitor sits in the room's waiting room, the doctor calls them in
export function visitorCard(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  room: Pick<Place, "name" | "room">,
  waiting: Pick<Place, "name" | "room">,
  links: CardLinks,
) {
  return teamsCard(visitorMessage(j, room, waiting, links));
}

export function helpCard(help: HelpRequest, boardUrl: string) {
  return teamsCard(helpMessage(help, boardUrl));
}

export function webhookFor(place?: Pick<Place, "teamsWebhookUrl"> | null) {
  return place?.teamsWebhookUrl || process.env.TEAMS_WEBHOOK_URL || undefined;
}

// posts to a teams workflows webhook. false when nothing is set up or teams said no.
// the channels in lib/notify.ts do the same with a reason; this stays for scripts that only want yes or no
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
