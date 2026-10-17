// browser helpers for the dashboard pages
import type { RobotCommand, VisitStatus } from "./types";

export const PAGE = "mx-auto w-full max-w-[1920px] px-4 sm:px-8";

export const BOARD_COLUMNS: VisitStatus[] = ["checked_in", "notified", "called", "escorting", "with_doctor", "done"];

export const STATUS_META: Record<VisitStatus, { label: string; dot: string }> = {
  checked_in: { label: "Checked in", dot: "bg-st-checked" },
  notified: { label: "Doctor notified", dot: "bg-st-notified" },
  called: { label: "Called in", dot: "bg-st-called" },
  escorting: { label: "Escorting", dot: "bg-st-escorting" },
  with_doctor: { label: "With doctor", dot: "bg-st-with" },
  done: { label: "Done", dot: "bg-st-done" },
  cancelled: { label: "Cancelled", dot: "bg-muted" },
};

export function robotLabel(robot: string) {
  return robot.charAt(0).toUpperCase() + robot.slice(1);
}

export function clockTime(iso: string) {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
}

// "walking" -> "Walking"
export function robotStateLabel(state: string) {
  const words = state.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// 4 -> "4 s ago", 300 -> "5 min ago", older -> "at 09:12"
export function seenAgo(iso: string, now: number) {
  const s = Math.max(0, Math.floor((now - Date.parse(iso)) / 1000));
  if (s < 1) return "just now";
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `at ${clockTime(iso)}`;
}

// "#2 in line, ~10 min". the first in line with a free doctor is next up
export function queueText(position: number, minutes: number | null) {
  return minutes ? `#${position} in line, ~${minutes} min` : `#${position} in line, next up`;
}

export type CommandState = "queued" | "delivered" | "done" | "cancelled";

export const COMMAND_STATE_META: Record<CommandState, { label: string; tone: string }> = {
  queued: { label: "Queued", tone: "bg-subtle text-muted" },
  delivered: { label: "Delivered", tone: "bg-primary-soft text-primary-ink" },
  done: { label: "Done", tone: "bg-ok-soft text-ok" },
  cancelled: { label: "Cancelled", tone: "bg-subtle text-muted" },
};

// closed before the robot fetched it = cancelled (a call for a visit that was closed)
export function commandState(c: RobotCommand): CommandState {
  if (c.doneAt) return c.deliveredAt ? "done" : "cancelled";
  return c.deliveredAt ? "delivered" : "queued";
}

export function commandText(c: RobotCommand) {
  if (c.type === "say") return `Say "${c.text}"`;
  if (c.type === "go_to") return `Go to ${c.spot}`;
  return `Bring ${c.patientName} to ${c.doctorName}`;
}

// 252 -> "4:12", 3720 -> "1 h 02 min"
export function duration(seconds: number) {
  const s = Math.max(0, Math.floor(seconds));
  if (s >= 3600) {
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    return `${h} h ${String(m).padStart(2, "0")} min`;
  }
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export function minutes(seconds: number) {
  if (seconds < 60) return `${Math.round(seconds)} s`;
  return `${Math.round(seconds / 60)} min`;
}

export function midnightIso() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d.toISOString();
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    cache: "no-store",
    ...init,
    headers: init?.body ? { "Content-Type": "application/json", ...init.headers } : init?.headers,
  });
  const data = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok) throw new Error((data as { error?: string } | null)?.error ?? `request failed (${res.status})`);
  return data as T;
}
