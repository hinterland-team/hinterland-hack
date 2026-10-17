"use client";

import { BellSlash, CheckCircle, Wheelchair, WarningCircle } from "@phosphor-icons/react";
import { CHANNEL_INFO, notifyEvent } from "@/lib/channel-types";
import type { Journey, JourneyWithQueue, Place } from "@/lib/types";
import { currentLeg } from "@/lib/types";
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

// what the messages to staff did for the current walk: "Sent via Teams, Slack", and each channel that failed with its reason
export function NotifyLine({ journey, places }: { journey: Journey; places: Place[] }) {
  const leg = currentLeg(journey);
  if (places.find((p) => p.id === leg.toPlaceId)?.kind === "exit") return null;
  const events = journey.events.filter((e) => e.at >= leg.createdAt && notifyEvent(e.type));
  if (!events.length) return null;
  const sent = [
    ...new Set(
      events.flatMap((e) => {
        const n = notifyEvent(e.type);
        return n?.outcome === "sent" ? [CHANNEL_INFO[n.channel].short] : [];
      }),
    ),
  ];
  const failed = events.filter((e) => notifyEvent(e.type)?.outcome === "failed");
  const skipped = !sent.length && !failed.length;
  return (
    <ul className="mt-2 grid gap-1 text-sm" aria-label={`Messages about ${journey.ticket}`}>
      {sent.length > 0 && (
        <li className="flex items-start gap-1.5 text-muted">
          <CheckCircle size={16} weight="fill" aria-hidden className="mt-0.5 shrink-0 text-ok" />
          <span className="min-w-0">Sent via {sent.join(", ")}</span>
        </li>
      )}
      {failed.map((e, i) => (
        <li key={`${e.at}-${i}`} className="flex items-start gap-1.5 text-danger">
          <WarningCircle size={16} weight="fill" aria-hidden className="mt-0.5 shrink-0" />
          <span className="min-w-0 break-words">{e.detail ?? "A message did not go out"}</span>
        </li>
      ))}
      {skipped && (
        <li className="flex items-start gap-1.5 text-muted">
          <BellSlash size={16} aria-hidden className="mt-0.5 shrink-0" />
          <span className="min-w-0">No channel set up, only this board</span>
        </li>
      )}
    </ul>
  );
}
