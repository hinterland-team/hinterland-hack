"use client";

import { Wheelchair } from "@phosphor-icons/react";
import type { JourneyWithQueue } from "@/lib/types";
import { PACE_LABEL, STAGE_TONE, type Stage } from "@/lib/ui";

export function TicketBadge({ ticket, stage, size = "md" }: { ticket: string; stage: Stage; size?: "md" | "lg" }) {
  const tone = STAGE_TONE[stage.key];
  return (
    <span
      className={`tabular flex shrink-0 items-center justify-center rounded-xl font-bold tracking-tight ${tone.bg} ${tone.text} ${
        size === "lg" ? "h-16 w-[88px] text-3xl" : "h-12 w-16 text-xl"
      }`}
      aria-label={`Ticket ${ticket}`}
    >
      {ticket}
    </span>
  );
}

const STEPS = ["Pickup", "Walking", "At the room", "With staff"];

// four short segments: pickup, walking, at the room, with staff
export function StageLine({ stage }: { stage: Stage }) {
  const tone = STAGE_TONE[stage.key];
  return (
    <span className="flex items-center gap-1" role="img" aria-label={`Step ${stage.step + 1} of 4: ${STEPS[stage.step]}`}>
      {STEPS.map((s, i) => (
        <span
          key={s}
          className={`h-1.5 flex-1 rounded-full ${i <= stage.step ? tone.dot : "bg-line"} ${
            stage.key === "failed" && i === stage.step ? "opacity-100" : ""
          }`}
        />
      ))}
    </span>
  );
}

export function PaceNote({ journey }: { journey: JourneyWithQueue }) {
  if (journey.pace === "normal" && !journey.wheelchair) return null;
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-subtle px-2 py-0.5 text-xs font-semibold text-muted">
      {journey.wheelchair && <Wheelchair size={14} aria-hidden />}
      {journey.wheelchair ? "Wheelchair" : PACE_LABEL[journey.pace]}
      {journey.wheelchair && journey.pace !== "normal" ? `, ${PACE_LABEL[journey.pace].toLowerCase()}` : ""}
    </span>
  );
}
