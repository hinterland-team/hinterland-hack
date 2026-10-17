"use client";

import type { RobotScreen as Screen, RobotStatus } from "@/lib/types";
import { Mascot } from "./mascot";

const VIEW_LABEL: Record<Screen["view"], string> = {
  idle: "Home",
  checkin: "Check-in",
  pickup: "Pickup",
  walking: "Walking",
  check_back: "Check-back",
  waiting: "Waiting",
  arrived: "Arrived",
  help: "Help",
};

// a small live copy of what the robot shows right now, built from its heartbeat
export function RobotScreen({ status }: { status: RobotStatus }) {
  const screen = status.online ? status.screen : null;
  return (
    <div
      className="relative aspect-video w-full overflow-hidden rounded-xl bg-screen text-screen-ink ring-1 ring-line"
      role="img"
      aria-label={
        screen
          ? `Otto's screen: ${screen.title}${screen.ticket ? `, ticket ${screen.ticket}` : ""}`
          : "Otto's screen is not available"
      }
    >
      {!screen ? (
        <div className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center">
          <span className="opacity-40 grayscale">
            <Mascot size={44} />
          </span>
          <p className="text-sm font-semibold text-screen-muted">
            {status.online ? "Otto has not sent its screen yet" : status.lastSeenAt ? "Otto is offline" : "Otto is not connected"}
          </p>
        </div>
      ) : screen.view === "idle" && !screen.ticket ? (
        <div key={`${screen.view}-${screen.title}`} className="rise flex h-full items-center gap-4 p-4 sm:p-5">
          <Mascot size={56} />
          <div className="min-w-0">
            <p className="text-lg leading-snug font-semibold break-words">{screen.title || "Hello, I'm Otto"}</p>
            {screen.step && <p className="mt-1 text-sm text-screen-muted">{screen.step}</p>}
          </div>
        </div>
      ) : (
        <div key={`${screen.view}-${screen.ticket}-${screen.title}`} className="rise flex h-full flex-col p-4 sm:p-5">
          <div className="flex items-start justify-between gap-3">
            <span
              className={`rounded-full px-2 py-0.5 text-xs font-semibold ${screen.view === "help" ? "bg-[#ff8189]/20 text-[#ffb3b8]" : "bg-white/10 text-screen-accent"}`}
            >
              {VIEW_LABEL[screen.view]}
            </span>
            <Mascot size={28} />
          </div>
          <div className="flex min-h-0 flex-1 items-center gap-4">
            {screen.ticket && (
              <p className="tabular text-5xl leading-none font-bold tracking-tight text-screen-accent">{screen.ticket}</p>
            )}
            <div className="min-w-0">
              <p className="text-base leading-snug font-semibold break-words sm:text-lg">{screen.title}</p>
              {screen.step && <p className="mt-1 text-sm leading-snug text-screen-muted break-words">{screen.step}</p>}
            </div>
          </div>
          {screen.progress !== undefined && (
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/12" aria-hidden>
              <div
                className="walk-line h-full rounded-full bg-screen-accent"
                style={{ width: `${Math.round(screen.progress * 100)}%` }}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}
