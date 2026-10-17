"use server";

import { headers } from "next/headers";
import { ROBOT_STATES } from "@/lib/types";

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

function clean(value: unknown, max: number) {
  return typeof value === "string" ? value.trim().slice(0, max) : "";
}

function robotName(value: unknown) {
  return clean(value, 40).toLowerCase() || "nova";
}

async function send(method: string, path: string, body?: unknown): Promise<RobotCall> {
  const h = await headers();
  const host = h.get("x-forwarded-host") ?? h.get("host");
  const proto = h.get("x-forwarded-proto") ?? "http";
  const out: Record<string, string> = {};
  const token = process.env.TEAM_INBOX_TOKEN;
  const forwarded = h.get("authorization");
  if (token) out.Authorization = `Bearer ${token}`;
  else if (forwarded) out.Authorization = forwarded;
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
    response = raw;
  }
  return { id: crypto.randomUUID(), at: new Date().toISOString(), method, path, body, status: res.status, response };
}

export async function simCheckIn(input: {
  patientName: string;
  doctor: string;
  appointmentTime: string;
  language: string;
  robot: string;
}) {
  const body: Record<string, string> = { patientName: clean(input.patientName, 120), robot: robotName(input.robot) };
  const doctor = clean(input.doctor, 120);
  const time = clean(input.appointmentTime, 20);
  const language = clean(input.language, 20);
  if (doctor) body.doctor = doctor;
  if (time) body.appointmentTime = time;
  if (language) body.language = language;
  return send("POST", "/api/visits", body);
}

export async function simPoll(robot: string) {
  return send("GET", `/api/robot/next?robot=${encodeURIComponent(robotName(robot))}`);
}

export async function simVisitStatus(visitId: string, status: "escorting" | "with_doctor") {
  if (!ID.test(visitId)) throw new Error("bad visit id");
  if (status !== "escorting" && status !== "with_doctor") throw new Error("bad status");
  return send("PATCH", `/api/visits/${visitId}`, { status });
}

export async function simCommandDone(commandId: string) {
  if (!ID.test(commandId)) throw new Error("bad command id");
  return send("POST", `/api/robot/commands/${commandId}/done`);
}

// state comes from a select, battery from a slider. the server stores odd values as null anyway
export async function simHeartbeat(input: {
  robot: string;
  battery: number;
  charging: boolean;
  state: string;
  position: string;
  goal: string;
}) {
  return send("POST", "/api/robot/heartbeat", {
    robot: robotName(input.robot),
    battery: Math.round(Math.min(100, Math.max(0, Number(input.battery) || 0))),
    charging: input.charging === true,
    state: (ROBOT_STATES as readonly string[]).includes(input.state) ? input.state : "idle",
    position: clean(input.position, 80) || null,
    goal: clean(input.goal, 200) || null,
  });
}

export async function simHelp(input: { summary: string; urgency: string; robot: string }) {
  return send("POST", "/api/help", {
    robot: robotName(input.robot),
    summary: clean(input.summary, 500),
    urgency: input.urgency === "urgent" ? "urgent" : "normal",
  });
}
