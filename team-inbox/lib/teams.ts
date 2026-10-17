import type { Doctor, HelpRequest, Visit } from "./types";

type CardElement = Record<string, unknown>;

// clock time for cards. CLINIC_TIMEZONE (e.g. Europe/Berlin) matters on servers that run in UTC
export function clock(isoTime: string) {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: process.env.CLINIC_TIMEZONE || undefined,
  }).format(new Date(isoTime));
}

export function robotLabel(robot: string) {
  return robot.charAt(0).toUpperCase() + robot.slice(1);
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

export function arrivalCard(visit: Visit, doctor: Doctor, callUrl: string) {
  return message(
    [
      title("Patient arrived"),
      line(`${robotLabel(visit.robot)} checked in a patient for ${doctor.name}.`),
      facts([
        ["Patient", visit.patientName],
        ["Doctor", doctor.name],
        ["Appointment", visit.appointmentTime ?? "No time given"],
        ["Checked in at", clock(visit.createdAt)],
      ]),
    ],
    [openUrl("Call patient in", callUrl, "positive")],
  );
}

export function unknownDoctorCard(visit: Visit, heard: string, boardUrl: string) {
  return message(
    [
      title("Patient arrived - doctor not found", "Warning"),
      line(`${robotLabel(visit.robot)} could not match "${heard}" to a doctor. Please pick the doctor on the reception board.`),
      facts([
        ["Patient", visit.patientName],
        ["Doctor heard", heard],
        ["Appointment", visit.appointmentTime ?? "No time given"],
        ["Checked in at", clock(visit.createdAt)],
      ]),
    ],
    [openUrl("Open reception board", boardUrl)],
  );
}

export function helpCard(help: HelpRequest, boardUrl: string) {
  const urgent = help.urgency === "urgent";
  return message(
    [
      title(urgent ? "Urgent: help needed at reception" : "Help needed at reception", urgent ? "Attention" : undefined),
      { type: "TextBlock", text: help.summary, wrap: true, spacing: "Medium" },
      facts([
        ["Robot", robotLabel(help.robot)],
        ["Time", clock(help.createdAt)],
      ]),
    ],
    [openUrl("Open reception board", boardUrl)],
  );
}

export function webhookFor(doctor?: Doctor | null) {
  return doctor?.teamsWebhookUrl || process.env.TEAMS_WEBHOOK_URL || undefined;
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
    console.error("teams webhook failed", err);
    return false;
  }
}
