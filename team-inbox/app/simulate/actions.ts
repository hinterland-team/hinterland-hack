"use server";

import { headers } from "next/headers";

// the simulator sends the same http requests as the robot app.
// the token is added here on the server and never reaches the browser

export type RobotCall = {
  id: string;
  at: string;
  method: string;
  path: string;
  body?: unknown;
  status: number;
  response: unknown;
};

const ID = /^[A-Za-z0-9-]{1,80}$/;

function robotName(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, 40).toLowerCase() : "nova";
}

async function send(method: string, path: string, body?: unknown): Promise<RobotCall> {
  const h = await headers();
  const host = h.get("x-forwarded-host") ?? h.get("host");
  const proto = h.get("x-forwarded-proto")?.split(",")[0] ?? "http";
  const out: Record<string, string> = {};
  const token = process.env.TEAM_INBOX_TOKEN;
  // without a token the robot routes are open; a bearer still marks the call as the robot's
  out.Authorization = `Bearer ${token ?? "simulator"}`;
  if (body !== undefined) out["Content-Type"] = "application/json";

  const res = await fetch(`${proto}://${host}${path}`, {
    method,
    headers: out,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  const raw = await res.text();
  let response: unknown = null;
  try {
    response = raw ? JSON.parse(raw) : null;
  } catch {
    response = raw.slice(0, 500);
  }
  return { id: crypto.randomUUID(), at: new Date().toISOString(), method, path, body, status: res.status, response };
}

export async function simPoll(robot: string) {
  return send("GET", `/api/robot/next?robot=${encodeURIComponent(robotName(robot))}`);
}

export async function simPatch(
  journeyId: string,
  body: { legId?: string; status?: string; event?: { type: string; detail?: string } },
) {
  if (!ID.test(journeyId)) throw new Error("bad journey id");
  return send("PATCH", `/api/journeys/${journeyId}`, body);
}

export async function simCommandDone(commandId: string) {
  if (!ID.test(commandId)) throw new Error("bad command id");
  return send("POST", `/api/robot/commands/${commandId}/done`);
}

export async function simHeartbeat(input: Record<string, unknown>) {
  return send("POST", "/api/robot/heartbeat", { ...input, robot: robotName(input.robot) });
}

export async function simFeed(input: {
  robot: string;
  journeyId?: string;
  items: { who: string; kind: string; text: string }[];
}) {
  const at = new Date().toISOString();
  return send("POST", "/api/robot/feed", {
    robot: robotName(input.robot),
    ...(input.journeyId ? { journeyId: input.journeyId } : {}),
    items: input.items.slice(0, 10).map((i) => ({ at, who: i.who, kind: i.kind, text: String(i.text).slice(0, 300) })),
  });
}

export async function simHelp(input: { robot: string; summary: string; urgency: string; ticket?: string; journeyId?: string }) {
  return send("POST", "/api/help", {
    robot: robotName(input.robot),
    summary: String(input.summary).slice(0, 500),
    urgency: input.urgency === "urgent" ? "urgent" : "normal",
    ...(input.ticket ? { ticket: input.ticket } : {}),
    ...(input.journeyId ? { journeyId: input.journeyId } : {}),
  });
}

// what the robot sends for a self check-in or a walk-in to a public place
export async function simCreate(input: { to: string; forPlaceId?: string; pace: string; wheelchair: boolean; robot: string }) {
  if (!ID.test(input.to) || (input.forPlaceId && !ID.test(input.forPlaceId))) throw new Error("bad place id");
  return send("POST", "/api/journeys", {
    to: input.to,
    ...(input.forPlaceId ? { forPlaceId: input.forPlaceId } : {}),
    pace: input.pace,
    wheelchair: input.wheelchair,
    robot: robotName(input.robot),
  });
}

export async function simPlaces() {
  return send("GET", "/api/places");
}

export async function simByTicket(ticket: string) {
  return send("GET", `/api/journeys/by-ticket/${encodeURIComponent(ticket.slice(0, 8))}`);
}
