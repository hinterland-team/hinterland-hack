"use client";

import { Bell, BellRinging, BellSlash, Info } from "@phosphor-icons/react";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { HelpRequest, JourneyWithQueue, Place } from "@/lib/types";
import { currentLeg } from "@/lib/types";
import { isSimRobot, placeText, robotLabel } from "@/lib/ui";
import { Button } from "./button";

// browser alerts for staff on this device: "A17 has arrived". the choice is kept per device (localStorage).
// works while the page is open (also in a background tab). phones need https for it

const KEY = "otto-alerts";
const listeners = new Set<() => void>();

function readChoice() {
  try {
    return window.localStorage.getItem(KEY) === "on";
  } catch {
    return false;
  }
}

function writeChoice(on: boolean) {
  try {
    window.localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // private mode: only for this page
  }
  memo = on;
  listeners.forEach((l) => l());
}

let memo: boolean | null = null;

function subscribe(cb: () => void) {
  listeners.add(cb);
  const onStorage = (e: StorageEvent) => {
    if (e.key === KEY) {
      memo = readChoice();
      cb();
    }
  };
  window.addEventListener("storage", onStorage);
  return () => {
    listeners.delete(cb);
    window.removeEventListener("storage", onStorage);
  };
}

function supported() {
  return typeof window !== "undefined" && "Notification" in window;
}

function snapshot() {
  if (memo === null) memo = readChoice();
  return memo && supported() && window.isSecureContext && Notification.permission === "granted";
}

// alerts are on for this device and the browser allows them
export function useAlertsOn() {
  return useSyncExternalStore(subscribe, snapshot, () => false);
}

async function show(title: string, body: string, tag: string) {
  const opts = { body, tag, icon: "/icon.svg", badge: "/icon.svg" };
  try {
    const n = new Notification(title, opts);
    n.onclick = () => {
      window.focus();
      n.close();
    };
    return;
  } catch {
    // android chrome: only through a service worker
  }
  try {
    const reg = (await navigator.serviceWorker.getRegistration("/")) ?? (await navigator.serviceWorker.register("/otto-alerts-sw.js"));
    await reg.showNotification(title, opts);
  } catch {
    // nothing more we can do
  }
}

type State = "unsupported" | "insecure" | "denied" | "off" | "on";

function useAlertState(): [State, (s: State) => void] {
  const on = useAlertsOn();
  const [, force] = useState(0);
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  const state: State = !mounted
    ? "off"
    : !supported()
      ? "unsupported"
      : !window.isSecureContext
        ? "insecure"
        : Notification.permission === "denied"
          ? "denied"
          : on
            ? "on"
            : "off";
  return [state, () => force((n) => n + 1)];
}

// the switch for this device, for the reception, the room view and Settings -> Notifications
export function AlertsControl({ what = "arrivals", wide = false }: { what?: string; wide?: boolean }) {
  const [state, refresh] = useAlertState();
  const [busy, setBusy] = useState(false);

  const turnOn = async () => {
    setBusy(true);
    try {
      const p = Notification.permission === "granted" ? "granted" : await Notification.requestPermission();
      if (p === "granted") {
        writeChoice(true);
        await show("Alerts are on", `This device now shows ${what}.`, "otto-test");
      }
    } finally {
      setBusy(false);
      refresh("off");
    }
  };

  if (state === "on") {
    return (
      <div className={`flex flex-wrap items-center gap-2 ${wide ? "" : "justify-end"}`}>
        <span className="inline-flex h-9 items-center gap-2 rounded-full bg-primary-soft px-3 text-sm font-semibold text-primary-ink">
          <BellRinging size={16} weight="fill" aria-hidden /> Alerts on
        </span>
        <Button kind="ghost" size="sm" onClick={() => show("Test alert", "This is how alerts look on this device.", "otto-test")}>
          Test
        </Button>
        <Button kind="ghost" size="sm" onClick={() => writeChoice(false)}>
          Turn off
        </Button>
      </div>
    );
  }
  if (state === "off") {
    return (
      <Button size="sm" onClick={turnOn} disabled={busy}>
        <Bell size={16} aria-hidden /> {busy ? "Asking the browser" : "Alerts on this device"}
      </Button>
    );
  }
  const text =
    state === "unsupported"
      ? "This browser can't show alerts."
      : state === "denied"
        ? "Alerts are blocked for this site in the browser's settings."
        : "Alerts need an https address on phones and other computers. On this laptop, open the dashboard at http://localhost:3000.";
  return (
    <p className={`flex items-start gap-1.5 text-sm text-muted ${wide ? "" : "max-w-xs"}`}>
      {state === "insecure" ? <Info size={16} aria-hidden className="mt-0.5 shrink-0" /> : <BellSlash size={16} aria-hidden className="mt-0.5 shrink-0" />}
      <span>{text}</span>
    </p>
  );
}

type Snap = Map<string, string>;

function keyOf(j: JourneyWithQueue) {
  const leg = currentLeg(j);
  return `${leg.id}:${leg.status}:${j.status}`;
}

// compares each board answer with the one before and alerts on arrivals, walks that need staff and help requests.
// only for the journeys `mine` picks (the room view: its own room). nothing on the first load
export function useStaffAlerts(
  journeys: JourneyWithQueue[] | undefined,
  places: Place[] | undefined,
  help: HelpRequest[] | undefined,
  mine: (j: JourneyWithQueue) => boolean = () => true,
) {
  const on = useAlertsOn();
  const seen = useRef<Snap | null>(null);
  const seenHelp = useRef<Set<string> | null>(null);
  const pick = useRef(mine);
  useEffect(() => {
    pick.current = mine;
  });

  useEffect(() => {
    if (!journeys || !places) return;
    const next: Snap = new Map(journeys.map((j) => [j.id, keyOf(j)]));
    const before = seen.current;
    seen.current = next;
    if (!before || !on) return;
    for (const j of journeys) {
      if (!pick.current(j)) continue;
      const k = next.get(j.id) as string;
      if (before.get(j.id) === k) continue;
      const leg = currentLeg(j);
      const sim = isSimRobot(j.robot) ? "Simulation: " : "";
      if (leg.status === "arrived" && j.status === "arrived") {
        const waiting = j.forPlaceId && j.forPlaceId !== leg.toPlaceId;
        const room = waiting ? places.find((p) => p.id === j.forPlaceId) : undefined;
        if (waiting) show(`${sim}${j.ticket} is in ${leg.toName}`, `Waiting for ${room?.name ?? "the room"}. Tap Call in when you are ready.`, `arrive-${leg.id}`);
        else show(`${sim}${j.ticket} has arrived`, `At ${placeText({ name: leg.toName, room: leg.toRoom })}.`, `arrive-${leg.id}`);
      } else if (leg.status === "failed") {
        const why = [...j.events].reverse().find((e) => e.type === "failed" || e.type === "lost_patient")?.detail;
        show(`${sim}${j.ticket} needs you`, why ?? "Otto could not finish the walk.", `failed-${leg.id}`);
      }
    }
  }, [journeys, places, on]);

  useEffect(() => {
    if (!help) return;
    const ids = new Set(help.map((h) => h.id));
    const before = seenHelp.current;
    seenHelp.current = ids;
    if (!before || !on) return;
    for (const h of help) {
      if (before.has(h.id)) continue;
      const sim = isSimRobot(h.robot) ? "Simulation: " : "";
      show(`${sim}${h.urgency === "urgent" ? "Urgent: " : ""}${robotLabel(h.robot)} needs a person`, `${h.ticket ? `Ticket ${h.ticket}. ` : ""}${h.summary}`, `help-${h.id}`);
    }
  }, [help, on]);
}
