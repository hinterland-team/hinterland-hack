// browser helpers for the dashboard pages
import type { Journey, JourneyWithQueue, Pace, Place, PlaceKind, RobotCommand, WalkAction } from "./types";
import { currentLeg } from "./types";

export const PAGE = "mx-auto w-full max-w-[1440px] px-4 sm:px-6 lg:px-8";
export const ROBOT = "nova";

export const PACE_LABEL: Record<Pace, string> = { normal: "Normal", slow: "Slow", very_slow: "Very slow" };

export const KIND_LABEL: Record<PlaceKind, string> = {
  room: "Room",
  waiting: "Waiting room",
  reception: "Reception",
  exit: "Exit",
  other: "Other",
};

export function robotLabel(robot: string) {
  return robot.charAt(0).toUpperCase() + robot.slice(1);
}

export function placeText(p: Pick<Place, "name" | "room"> | { name: string; room?: string }) {
  return p.room ? `${p.name}, Room ${p.room}` : p.name;
}

export function clockTime(iso: string) {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
}

// 40 s -> "just now", 300 s -> "5 min"
export function sinceText(iso: string, now: number) {
  const s = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))} min`;
  const h = Math.floor(s / 3600);
  return `${h} h ${Math.round((s % 3600) / 60)} min`;
}

export function seenAgo(iso: string, now: number) {
  const s = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (s < 5) return "just now";
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `at ${clockTime(iso)}`;
}

export function ordinal(n: number) {
  const s = n % 100 >= 11 && n % 100 <= 13 ? "th" : (({ 1: "st", 2: "nd", 3: "rd" } as Record<number, string>)[n % 10] ?? "th");
  return `${n}${s}`;
}

export function waitText(minutes: number | null) {
  if (minutes === null) return "";
  return minutes <= 0 ? "next" : `about ${minutes} min`;
}

export type StageKey = "pickup" | "walking" | "waiting" | "room" | "staff" | "failed";

export const STAGE_TONE: Record<StageKey, { text: string; bg: string; dot: string }> = {
  pickup: { text: "text-st-pickup", bg: "bg-subtle", dot: "bg-st-pickup" },
  walking: { text: "text-st-walking", bg: "bg-primary-soft", dot: "bg-st-walking" },
  waiting: { text: "text-st-waiting", bg: "bg-subtle", dot: "bg-st-waiting" },
  room: { text: "text-st-waiting", bg: "bg-subtle", dot: "bg-st-waiting" },
  staff: { text: "text-st-staff", bg: "bg-ok-soft", dot: "bg-st-staff" },
  failed: { text: "text-danger", bg: "bg-danger-soft", dot: "bg-danger" },
};

export type Stage = { key: StageKey; title: string; detail: string; step: number };

function lastEventAt(j: Journey, type: string) {
  return [...j.events].reverse().find((e) => e.type === type);
}

// what the journey is doing, in plain words. step 0-3 for the progress line
export function stageOf(j: JourneyWithQueue, now: number): Stage {
  const leg = currentLeg(j);
  const to = placeText({ name: leg.toName, room: leg.toRoom });
  const line =
    j.queuePosition !== null
      ? `${ordinal(j.queuePosition)} in line${j.estimatedWaitMinutes ? `, about ${j.estimatedWaitMinutes} min` : ", next"}`
      : "";
  if (leg.status === "failed") {
    const why = lastEventAt(j, "failed")?.detail ?? lastEventAt(j, "lost_patient")?.detail ?? "Nova could not finish the walk";
    return { key: "failed", title: "Needs you", detail: why, step: leg.startedAt ? 1 : 0 };
  }
  if (j.status === "with_staff") {
    const since = lastEventAt(j, "with_staff")?.at ?? j.updatedAt;
    return { key: "staff", title: "With staff", detail: `${to}, for ${sinceText(since, now)}`, step: 3 };
  }
  if (j.status === "arrived") {
    const waitingRoom = j.forPlaceId && j.forPlaceId !== leg.toPlaceId;
    return {
      key: waitingRoom ? "waiting" : "room",
      title: waitingRoom ? `In ${leg.toName}` : "At the room",
      detail: [waitingRoom ? "" : to, line].filter(Boolean).join(", ") || to,
      step: 2,
    };
  }
  if (j.status === "walking") {
    return { key: "walking", title: "On the way", detail: `to ${to}, ${sinceText(leg.startedAt ?? j.updatedAt, now)}`, step: 1 };
  }
  const from = leg.fromName ?? "the pickup point";
  return {
    key: "pickup",
    title: leg.status === "pickup" ? "Nova is picking up" : "Nova is coming",
    detail: leg.status === "pickup" ? `at ${from}, going to ${leg.toName}` : `to ${from}, then ${leg.toName}`,
    step: 0,
  };
}

export type CommandState = "queued" | "delivered" | "done" | "cancelled";

// closed before the robot fetched it = cancelled
export function commandState(c: RobotCommand): CommandState {
  if (c.doneAt) return c.deliveredAt ? "done" : "cancelled";
  return c.deliveredAt ? "delivered" : "queued";
}

export const WALK_ACTION_LABEL: Record<WalkAction, string> = {
  wait: "Wait",
  resume: "Go on",
  slower: "Slower",
  faster: "Faster",
};

export const WALK_ACTION_DONE: Record<WalkAction, string> = {
  wait: "Stopped and waits for the patient",
  resume: "Walks on",
  slower: "Walks slower",
  faster: "Walks faster",
};

export function commandText(c: RobotCommand) {
  if (c.type === "say") return `Say "${c.text}"`;
  if (c.type === "ask") {
    if (c.origin === "phone") return c.text ? `Patient asked on the phone: "${c.text}"` : "Patient asked something on the phone";
    return `Asked: "${c.text}"`;
  }
  if (c.type === "go_to") return `Go to ${c.spot}`;
  if (c.type === "walk_control") return `${WALK_ACTION_LABEL[c.action]} (${c.origin === "phone" ? "patient's phone" : "board"})`;
  return `Guide ${c.ticket} to ${placeText({ name: c.to.name, room: c.to.room })}`;
}

export class LoginNeeded extends Error {}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    cache: "no-store",
    ...init,
    headers: init?.body ? { "Content-Type": "application/json", ...init.headers } : init?.headers,
  });
  if (res.status === 401 && typeof window !== "undefined") {
    const next = window.location.pathname + window.location.search;
    window.location.assign(`/login${next === "/" ? "" : `?next=${encodeURIComponent(next)}`}`);
    throw new LoginNeeded("login needed");
  }
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new Error((data as { error?: string } | null)?.error ?? `request failed (${res.status})`);
  return data as T;
}

export function post<T>(path: string, body?: unknown, method = "POST") {
  return api<T>(path, { method, body: JSON.stringify(body ?? {}) });
}
