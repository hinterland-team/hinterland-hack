// what staff get told: a patient on the way, a visitor in the waiting room, otto asking for help, a test.
// every channel renders this same message its own way (lib/notify.ts). only the ticket and places, never a name
import type { HelpRequest, Journey, Pace, Place } from "./types";

// clock time for messages. CLINIC_TIMEZONE (e.g. Europe/Berlin) matters on servers that run in UTC
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

export type CardLinks = { exit?: string; done: string; callIn?: string };

export type MessageLink = { label: string; url: string; primary?: boolean };
export type MessageKind = "on_the_way" | "visitor" | "help" | "test";

export type NotifyMessage = {
  kind: MessageKind;
  title: string;
  // one sentence under the title
  text: string;
  // help: the text is the main content, not a side note
  textStrong?: boolean;
  urgent?: boolean;
  facts: [string, string][];
  links: MessageLink[];
  // the same content as plain fields, for the json webhook. never a name or anything about health
  data: Record<string, string | boolean>;
};

function paceText(j: Pick<Journey, "pace" | "wheelchair">) {
  return `${PACE_TEXT[j.pace]}${j.wheelchair ? ", wheelchair" : ""}`;
}

// "A17 is on the way" to the place otto walks to
export function onTheWayMessage(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  to: Pick<Place, "name" | "room">,
  expected: string,
  links: CardLinks,
): NotifyMessage {
  const out: MessageLink[] = [];
  if (links.exit) out.push({ label: "Done - guide to exit", url: links.exit, primary: true });
  out.push({ label: "Done - finished here", url: links.done });
  return {
    kind: "on_the_way",
    title: `${j.ticket} is on the way`,
    text: "Otto is walking this patient to you. Otto only knows the ticket and the room.",
    facts: [
      ["Ticket", j.ticket],
      ["To", placeLabel(to)],
      ["Pace", paceText(j)],
      ["Expected in", expected],
    ],
    links: out,
    data: {
      ticket: j.ticket,
      place: to.name,
      ...(to.room ? { room: to.room } : {}),
      pace: j.pace,
      wheelchair: j.wheelchair,
      expected,
    },
  };
}

// self check-in or "via the waiting room": the visitor sits there, the room calls them in
export function visitorMessage(
  j: Pick<Journey, "ticket" | "pace" | "wheelchair">,
  room: Pick<Place, "name" | "room">,
  waiting: Pick<Place, "name" | "room">,
  links: CardLinks,
): NotifyMessage {
  const out: MessageLink[] = [];
  if (links.callIn) out.push({ label: "Call in", url: links.callIn, primary: true });
  out.push({ label: links.exit ? "Done - guide to exit" : "Done - finished here", url: links.exit ?? links.done });
  return {
    kind: "visitor",
    title: `Your visitor has arrived - ticket ${j.ticket}, waiting in ${waiting.name}`,
    text: "Tap Call in when you are ready. Otto picks the ticket up in the waiting room and walks them to you.",
    facts: [
      ["Ticket", j.ticket],
      ["Waiting in", placeLabel(waiting)],
      ["For", placeLabel(room)],
      ["Pace", paceText(j)],
    ],
    links: out,
    data: {
      ticket: j.ticket,
      place: room.name,
      ...(room.room ? { room: room.room } : {}),
      waitingRoom: waiting.name,
      pace: j.pace,
      wheelchair: j.wheelchair,
    },
  };
}

export function helpMessage(help: HelpRequest, boardUrl: string): NotifyMessage {
  const urgent = help.urgency === "urgent";
  const facts: [string, string][] = [["Robot", robotLabel(help.robot)]];
  if (help.ticket) facts.push(["Ticket", help.ticket]);
  facts.push(["Time", clock(help.createdAt)]);
  return {
    kind: "help",
    title: urgent ? "Urgent: help needed at reception" : "Help needed at reception",
    text: help.summary,
    textStrong: true,
    urgent,
    facts,
    links: [{ label: "Open reception", url: boardUrl }],
    data: { robot: help.robot, urgency: help.urgency, ...(help.ticket ? { ticket: help.ticket } : {}), time: help.createdAt },
  };
}

// "Send test" on the notifications page. honest about being a test, no example patient
export function testMessage(channel: string, base: string, at: string): NotifyMessage {
  return {
    kind: "test",
    title: "Test message from Nova Clinic",
    text: "This channel works. Real messages look like this one: the ticket, the place, the pace and two links. Never a name.",
    facts: [
      ["Channel", channel],
      ["Sent", clock(at)],
    ],
    links: [
      { label: "Open reception", url: `${base}/`, primary: true },
      { label: "Open notifications", url: `${base}/notifications` },
    ],
    data: { test: true, channel, time: at },
  };
}
