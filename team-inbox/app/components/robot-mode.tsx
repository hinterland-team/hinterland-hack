"use client";

import { useState, useSyncExternalStore } from "react";
import { MODE_INFO, OTTO_MODES, type OttoMode, blockedText } from "@/lib/modes";
import { LoginNeeded, ROBOT, clockTime, goToLogin, post } from "@/lib/ui";
import { Button } from "./button";

// otto's mode (normal / show / calm), the same for every part of the page: one shared poll of /api/robot/mode

// lib/config.ts ModeView: mode = the one that counts, saved = the dashboard's choice
export type ModeState = {
  robot: string;
  mode: OttoMode;
  saved: OttoMode;
  applied: { mode: OttoMode; at: string } | null;
  pending: OttoMode | null;
  switching: boolean;
  onRobot: boolean;
  refused: string | null;
  online: boolean;
};

let current: ModeState | null = null;
let etag: string | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
let polling = false;
const subs = new Set<() => void>();

function emit() {
  subs.forEach((s) => s());
}

async function load() {
  try {
    const res = await fetch(`/api/robot/mode?robot=${ROBOT}`, { cache: "no-store", headers: etag ? { "If-None-Match": etag } : undefined });
    if (res.status === 401) goToLogin();
    if (res.status === 304 || !res.ok) return;
    etag = res.headers.get("ETag");
    current = (await res.json()) as ModeState;
    emit();
  } catch {
    // keeps the last known mode
  }
}

function plan() {
  clearTimeout(timer);
  if (!subs.size || document.visibilityState !== "visible") return;
  // quicker while otto has not taken a new mode yet
  const waiting = current && current.online && (current.switching || current.pending);
  timer = setTimeout(async () => {
    await load();
    plan();
  }, waiting ? 2500 : 10_000);
}

function onVisible() {
  if (document.visibilityState === "visible") load().then(plan);
  else clearTimeout(timer);
}

function subscribe(cb: () => void) {
  subs.add(cb);
  if (!polling) {
    polling = true;
    document.addEventListener("visibilitychange", onVisible);
    load().then(plan);
  }
  return () => {
    subs.delete(cb);
    if (!subs.size) {
      polling = false;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    }
  };
}

export function useRobotMode() {
  return useSyncExternalStore(subscribe, () => current, () => null);
}

// demo: demo start with the saved script; from demo to another mode: demo stop, then set_mode
export async function switchMode(mode: OttoMode) {
  const next = await post<ModeState>("/api/robot/mode", { robot: ROBOT, mode });
  current = next;
  etag = null;
  emit();
  plan();
  return next;
}

const DOT: Record<OttoMode, string> = { normal: "bg-ok", show: "bg-primary", calm: "bg-st-waiting", demo: "bg-warn" };

// the switch at the top of every page
export function ModeSwitch() {
  const state = useRobotMode();
  const [busy, setBusy] = useState<OttoMode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mode = state?.mode ?? "normal";
  const pending = Boolean(state && (state.switching || state.pending));

  const pick = async (m: OttoMode) => {
    if (m === mode || busy) return;
    setBusy(m);
    setError(null);
    try {
      await switchMode(m);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not switch");
    }
    setBusy(null);
  };

  return (
    <div className="flex items-center gap-2">
      <div
        role="radiogroup"
        aria-label="Otto's mode"
        className="flex items-center gap-0.5 rounded-xl border border-line bg-subtle p-0.5"
        title={error ?? (state ? (modeLine(state) ?? MODE_INFO[mode].help) : MODE_INFO[mode].help)}
      >
        <span className="hidden px-2 text-xs font-semibold text-muted min-[440px]:inline">Otto</span>
        {OTTO_MODES.map((m) => {
          const on = m === mode;
          return (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={on}
              disabled={state === null}
              onClick={() => pick(m)}
              title={MODE_INFO[m].help}
              className={`press flex h-8 items-center gap-1.5 rounded-lg px-2 text-[13px] font-semibold disabled:opacity-60 min-[440px]:px-2.5 ${
                on ? "bg-surface text-ink shadow-panel" : "text-muted hover:text-ink"
              }`}
            >
              {/* narrow phones: no dots, so four modes fit next to the logo */}
              <span aria-hidden className={`size-2 rounded-full max-[439px]:hidden ${on ? DOT[m] : "bg-line-strong"} ${on && pending ? "live-dot" : ""}`} />
              {busy === m ? "…" : MODE_INFO[m].label}
            </button>
          );
        })}
      </div>
    </div>
  );
}

const label = (m: OttoMode) => MODE_INFO[m].label;

// the robot card's line: "Show mode, since 18:42", "Switching to Show mode", "Normal mode until this walk is over"
export function modeLine(state: ModeState | null) {
  if (!state) return null;
  if (state.refused) return `did not switch to ${label(state.saved)} mode: ${state.refused}`;
  if (state.switching) return state.online ? `Switching to ${label(state.saved)} mode, waiting for Otto` : `${label(state.saved)} mode once Otto is back online`;
  const since = state.applied ? `, since ${clockTime(state.applied.at)}` : "";
  if (state.pending && state.applied) {
    return `${label(state.applied.mode)} mode until this walk is over, then ${label(state.pending)}${state.onRobot ? " (picked on Otto)" : ""}`;
  }
  if (state.onRobot) return `${label(state.mode)} mode, picked on Otto${since}. Pick a mode here to change it`;
  return `${label(state.mode)} mode${since}`;
}

// where a new walk would start: a friendly stop in show and calm mode, with the way back
export function ModeBlock({ className = "" }: { className?: string }) {
  const state = useRobotMode();
  const [busy, setBusy] = useState(false);
  if (!state || state.mode === "normal") return null;
  return (
    <div className={`flex flex-wrap items-center gap-3 rounded-xl bg-warn-soft px-4 py-3 text-[15px] text-warn ${className}`} role="status">
      <span className="min-w-0 flex-1 basis-56 font-medium">{blockedText(state.mode)}.</span>
      <Button
        size="sm"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          try {
            await switchMode("normal");
          } finally {
            setBusy(false);
          }
        }}
      >
        Switch to Normal
      </Button>
    </div>
  );
}
