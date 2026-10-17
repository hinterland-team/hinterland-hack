"use client";

import { ArrowLeft, Armchair, Footprints, UserCheck, Warning } from "@phosphor-icons/react";
import Link from "next/link";
import type { JourneyWithQueue, Place } from "@/lib/types";
import { currentLeg, lineKey } from "@/lib/types";
import { PAGE, STAGE_TONE, type Stage, isSimRobot, placeText, stageOf } from "@/lib/ui";
import { Button, Dot } from "../../components/button";
import { JourneyActions, type Run } from "../../components/journey-actions";
import { PaceNote, StageLine, TicketBadge } from "../../components/journey-bits";
import { ModeBlock } from "../../components/robot-mode";
import { SimBadge } from "../../components/sim-badge";
import { AlertsControl, useStaffAlerts } from "../../components/staff-alerts";
import { useAction, useBoard } from "../../components/use-board";
import { useNow } from "../../components/use-poll";

type Row = { j: JourneyWithQueue; stage: Stage };

export function RoomView({ placeId }: { placeId: string }) {
  const { data, offline, reload } = useBoard(2500);
  const now = useNow(15_000);
  const { run, busy, error, clearError } = useAction(reload);
  // alerts for this room only: who arrived in its waiting room or at the door
  useStaffAlerts(data?.journeys, data?.places, undefined, (j) => lineKey(j) === placeId || currentLeg(j).toPlaceId === placeId);

  if (!data || now === null) {
    return (
      <main className={`${PAGE} pt-6 pb-16 *:max-w-3xl`}>
        <div className="h-10 w-2/3 animate-pulse rounded-xl bg-subtle motion-reduce:animate-none" />
        <div className="mt-6 h-64 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
        {offline && <p className="mt-4 text-[15px] text-muted">Can&apos;t reach the server. Retrying.</p>}
      </main>
    );
  }

  const place = data.places.find((p) => p.id === placeId);
  if (!place) {
    return (
      <main className={`${PAGE} pt-8 pb-16 *:max-w-3xl`}>
        <h1 className="text-2xl font-semibold">This room is not on the list</h1>
        <p className="mt-2 text-[15px] text-muted">It may have been removed under Settings, Places.</p>
        <Link
          href="/room"
          className="mt-6 inline-flex h-12 items-center gap-2 rounded-xl bg-primary px-5 font-semibold text-on-primary"
        >
          <ArrowLeft size={18} aria-hidden /> Pick a room
        </Link>
      </main>
    );
  }

  const waitingRoom = place.waitingPlaceId ? data.places.find((p) => p.id === place.waitingPlaceId) : undefined;
  const mine = data.journeys
    .filter((j) => lineKey(j) === place.id || currentLeg(j).toPlaceId === place.id)
    .map((j) => ({ j, stage: stageOf(j, now) }));
  const byQueue = (a: Row, b: Row) =>
    (a.j.queuePosition ?? 99) - (b.j.queuePosition ?? 99) || a.j.createdAt.localeCompare(b.j.createdAt);
  const failed = mine.filter((r) => r.stage.key === "failed");
  const here = mine
    .filter((r) => r.stage.key === "staff" || r.stage.key === "room")
    .sort((a, b) => (a.stage.key === "staff" ? -1 : 0) - (b.stage.key === "staff" ? -1 : 0) || byQueue(a, b));
  const waiting = mine.filter((r) => r.stage.key === "waiting").sort(byQueue);
  const coming = mine
    .filter((r) => r.stage.key === "walking" || r.stage.key === "pickup")
    .sort((a, b) => b.stage.step - a.stage.step || byQueue(a, b));

  return (
    <main className={`${PAGE} pt-5 pb-16 *:max-w-3xl`}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-[28px] leading-tight font-semibold tracking-tight break-words">{place.name}</h1>
          <p className="text-[15px] text-muted">
            {[place.room ? `Room ${place.room}` : "", waitingRoom ? `waiting room: ${waitingRoom.name}` : ""]
              .filter(Boolean)
              .join(", ") || "Room view"}
          </p>
        </div>
        <Link
          href="/room"
          className="flex h-11 items-center gap-1 rounded-xl px-3 text-sm font-semibold text-primary hover:bg-primary-soft"
        >
          Other room
        </Link>
      </div>
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <p className={`flex items-center gap-2 text-sm ${offline ? "text-danger" : "text-muted"}`}>
          <Dot className={offline ? "bg-danger" : "bg-ok"} live={!offline} />
          {offline ? "Can't reach the server, retrying" : "Live. Tickets only, no names."}
        </p>
        <AlertsControl what={`"A17 has arrived" for ${place.name}`} />
      </div>
      <ModeBlock className="mt-4" />

      {error && (
        <div
          role="alert"
          className="mt-4 flex items-center gap-3 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger"
        >
          <span className="flex-1">{error}</span>
          <Button kind="ghost" size="sm" onClick={clearError}>
            OK
          </Button>
        </div>
      )}

      {mine.length === 0 && (
        <div className="mt-6 rounded-2xl border border-line bg-surface p-6 text-center shadow-panel">
          <Footprints size={36} aria-hidden className="mx-auto text-muted" />
          <h2 className="mt-3 text-lg font-semibold">Nobody is on the way to {place.name}</h2>
          <p className="mx-auto mt-1 max-w-sm text-[15px] text-muted">
            When reception sends Otto here, the ticket shows up on this screen with its arrival time. Keep this page open.
          </p>
        </div>
      )}

      <Section
        title="Needs you"
        icon={<Warning size={20} aria-hidden className="text-danger" />}
        rows={failed}
        places={data.places}
        run={run}
        busy={busy}
      />
      <Section
        title="Here"
        icon={<UserCheck size={20} aria-hidden className="text-st-staff" />}
        rows={here}
        places={data.places}
        run={run}
        busy={busy}
      />
      <Section
        title={waitingRoom ? `In ${waitingRoom.name}` : "In the waiting room"}
        icon={<Armchair size={20} aria-hidden className="text-st-waiting" />}
        rows={waiting}
        places={data.places}
        run={run}
        busy={busy}
      />
      <Section
        title="Coming"
        icon={<Footprints size={20} aria-hidden className="text-st-walking" />}
        rows={coming}
        places={data.places}
        run={run}
        busy={busy}
      />
    </main>
  );
}

function Section({
  title,
  icon,
  rows,
  places,
  run,
  busy,
}: {
  title: string;
  icon: React.ReactNode;
  rows: Row[];
  places: Place[];
  run: Run;
  busy: string | null;
}) {
  if (!rows.length) return null;
  return (
    <section className="mt-6" aria-label={title}>
      <h2 className="flex items-center gap-2 text-sm font-semibold text-muted">
        {icon} {title} <span className="tabular">({rows.length})</span>
      </h2>
      <ul className="mt-2 grid gap-3">
        {rows.map(({ j, stage }) => (
          <li key={j.id} className="rise rounded-2xl border border-line bg-surface p-4 shadow-panel">
            <div className="flex items-start gap-4">
              <TicketBadge ticket={j.ticket} stage={stage} size="lg" />
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2">
                  <span className={`text-lg font-semibold ${STAGE_TONE[stage.key].text}`}>{stage.title}</span>
                  <PaceNote journey={j} />
                  {isSimRobot(j.robot) && <SimBadge />}
                </p>
                <p className="text-[15px] text-muted tabular">
                  {stage.detail || placeText({ name: currentLeg(j).toName, room: currentLeg(j).toRoom })}
                </p>
                <div className="mt-2">
                  <StageLine stage={stage} />
                </div>
              </div>
            </div>
            <div className="mt-4">
              <JourneyActions journey={j} stage={stage} places={places} run={run} busy={busy} big />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
