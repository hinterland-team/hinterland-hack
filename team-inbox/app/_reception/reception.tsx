"use client";

import { BellRinging, ThumbsDown, ThumbsUp, WarningOctagon } from "@phosphor-icons/react";
import { memo, useMemo, useState } from "react";
import type { Board } from "@/lib/board";
import type { HelpRequest, RobotStatus } from "@/lib/types";
import { PAGE, ROBOT, SIM_ROBOT, clockTime, post, robotLabel } from "@/lib/ui";
import { Button, Dot } from "../components/button";
import { useLiveMap } from "../components/live-map";
import { AlertsControl, useStaffAlerts } from "../components/staff-alerts";
import { useAction, useBoard } from "../components/use-board";
import { useNow } from "../components/use-poll";
import { GuidePanel } from "./guide-panel";
import { JourneysPanel } from "./journeys-panel";
import { ControlsCard, ConversationCard, RobotCard } from "./live-panel";
import { MapPanel } from "./map-panel";

export function Reception() {
  // the live card can show virtual otto while a simulation runs (/simulate). the list always shows every walk
  const [robot, setRobot] = useState(ROBOT);
  const { data, offline, reload } = useBoard(2000, robot);
  const live = useLiveMap(robot);
  const { run, busy, error, clearError } = useAction(reload);
  const simOpen = useMemo(() => Boolean(data?.journeys.some((j) => j.robot === SIM_ROBOT)), [data?.journeys]);
  const showSwitch = simOpen || robot === SIM_ROBOT;
  useStaffAlerts(data?.journeys, data?.places, data?.help);

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3 pt-6 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Reception</h1>
          <DateLine />
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          <AlertsControl what={`"A17 has arrived" and other updates for the reception`} />
          <LiveBadge offline={offline} ready={data !== null} />
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger"
        >
          <span className="flex-1">{error}</span>
          <Button kind="ghost" size="sm" onClick={clearError}>
            Dismiss
          </Button>
        </div>
      )}

      {data && data.robot.online && data.robot.state === "error" && <OutOfService robot={data.robot} />}

      {data && data.help.length > 0 && (
        <div className="mb-4 grid gap-3">
          {data.help.map((h) => (
            <HelpAlert
              key={h.id}
              help={h}
              busy={busy === `help:${h.id}`}
              onDone={() => run(`help:${h.id}`, () => post(`/api/help/${h.id}`, { status: "done" }, "PATCH"))}
            />
          ))}
        </div>
      )}

      {data ? (
        <>
          <Today stats={data.stats} />
          <div className="mt-4 grid items-start gap-4 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] xl:grid-cols-[minmax(0,4fr)_minmax(0,5fr)_minmax(0,4fr)] xl:gap-5">
            <GuidePanel places={data.places} open={data.journeys} robotStatus={data.robot} onSent={reload} />
            <JourneysPanel journeys={data.journeys} places={data.places} run={run} busy={busy} />
            <div className="grid items-start gap-4 lg:col-span-2 lg:grid-cols-2 xl:col-span-1 xl:grid-cols-1 xl:gap-5">
              <RobotCard
                robot={data.robot}
                journeys={data.journeys}
                currentJourneyId={data.currentJourneyId}
                logErrors={data.logErrors}
                places={data.places}
                floors={live.floors}
                run={run}
                busy={busy}
                pick={showSwitch ? setRobot : undefined}
              />
              <MapPanel places={data.places} robot={data.robot} live={live} />
              <ConversationCard feed={data.feed} online={data.robot.online} robot={data.robot.robot} run={run} />
              <ControlsCard places={data.places} commands={data.commands} robot={data.robot} run={run} busy={busy} />
            </div>
          </div>
        </>
      ) : (
        <Skeleton offline={offline} />
      )}
    </main>
  );
}

// the date and time under the title: its own clock, so the rest of the page does not re-render every second
function DateLine() {
  const now = useNow(15_000);
  return (
    <p className="text-[15px] text-muted tabular">
      {now
        ? `${new Date(now).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" })}, ${clockTime(new Date(now).toISOString())}`
        : " "}
    </p>
  );
}

function LiveBadge({ offline, ready }: { offline: boolean; ready: boolean }) {
  if (offline) {
    return (
      <span className="inline-flex h-9 items-center gap-2 rounded-full bg-danger-soft px-3 text-sm font-semibold text-danger">
        <Dot className="bg-danger" /> Can&apos;t reach the server, retrying
      </span>
    );
  }
  return (
    <span className="inline-flex h-9 items-center gap-2 rounded-full bg-ok-soft px-3 text-sm font-semibold text-ok">
      <Dot className="bg-ok" live={ready} /> Live
    </span>
  );
}

function OutOfService({ robot }: { robot: RobotStatus }) {
  return (
    <div role="alert" className="rise mb-4 flex flex-wrap items-center gap-4 rounded-2xl border-2 border-danger bg-danger-soft px-4 py-4 sm:px-6">
      <WarningOctagon size={32} weight="fill" aria-hidden className="text-danger" />
      <div className="min-w-0 flex-1 basis-60">
        <p className="text-[17px] font-semibold text-danger">Out of service: {robotLabel(robot.robot)} can&apos;t drive right now</p>
        <p className="text-[15px] break-words">{robot.detail ?? "The robot reported an error."}</p>
        <p className="text-sm text-muted">
          Not started from the home screen: start Otto&apos;s app (Nova Clinic) again from the robot&apos;s home screen. Emergency
          stop, low battery or an update: check the robot.
        </p>
      </div>
    </div>
  );
}

function HelpAlert({ help, onDone, busy }: { help: HelpRequest; onDone: () => void; busy: boolean }) {
  const urgent = help.urgency === "urgent";
  return (
    <div
      role="alert"
      className={`rise flex flex-wrap items-center gap-4 rounded-2xl border-2 px-4 py-4 sm:px-6 ${urgent ? "border-danger bg-danger-soft" : "border-warn bg-warn-soft"}`}
    >
      <BellRinging size={32} weight="fill" aria-hidden className={urgent ? "text-danger" : "text-warn"} />
      <div className="min-w-0 flex-1 basis-60">
        <p className={`text-[17px] font-semibold ${urgent ? "text-danger" : "text-warn"}`}>
          {urgent ? "Urgent: " : ""}
          {robotLabel(help.robot)} needs a person{help.ticket ? ` for ticket ${help.ticket}` : ""}
        </p>
        <p className="text-[15px]">{help.summary}</p>
        <p className="text-sm text-muted">
          {clockTime(help.createdAt)}, {help.notified ? "also sent to the team's channels" : "only on this board"}
        </p>
      </div>
      <Button kind={urgent ? "danger" : "primary"} size="lg" disabled={busy} onClick={onDone}>
        I&apos;m on it
      </Button>
    </div>
  );
}

const Today = memo(function Today({ stats: s }: { stats: Board["stats"] }) {
  const cells: { label: string; value: string; note?: React.ReactNode }[] = [
    { label: "Guides today", value: String(s.guides), note: `${s.journeys} patients` },
    { label: "Finished", value: String(s.done), note: `${s.activeNow} still open` },
    {
      label: "Average walk",
      value: s.avgWalkMinutes === null ? "None yet" : s.avgWalkMinutes < 1 ? "Under 1 min" : `${s.avgWalkMinutes} min`,
      note: s.avgWalkMinutes === null ? "From pickup to the room" : "pickup to the room",
    },
    { label: "Lost patients", value: String(s.lostPatients), note: s.lostPatients ? "Otto asked for help" : "Nobody got lost" },
    {
      label: "Walk feedback",
      value: s.feedbackUp + s.feedbackDown === 0 ? "None yet" : `${s.feedbackUp} good`,
      note:
        s.feedbackUp + s.feedbackDown === 0 ? (
          "Patients rate it on the robot"
        ) : (
          <span className="inline-flex items-center gap-1.5">
            <ThumbsUp size={14} aria-hidden /> {s.feedbackUp} up, <ThumbsDown size={14} aria-hidden /> {s.feedbackDown} down
          </span>
        ),
    },
  ];
  return (
    <section
      aria-label="Today in numbers, anonymous"
      className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl border border-line bg-line sm:grid-cols-3 lg:grid-cols-5"
    >
      {cells.map((c, i) => (
        <div key={c.label} className={`bg-surface px-4 py-3 sm:px-5 ${i === cells.length - 1 ? "col-span-2 lg:col-span-1" : ""}`}>
          <p className="text-sm text-muted">{c.label}</p>
          <p className="text-2xl font-semibold tracking-tight tabular">{c.value}</p>
          {c.note && <p className="text-sm text-muted">{c.note}</p>}
        </div>
      ))}
    </section>
  );
});

function Skeleton({ offline }: { offline: boolean }) {
  return (
    <div aria-busy="true" aria-label={offline ? "Can't reach the server" : "Loading"}>
      <div className="h-[92px] animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
      <div className="mt-4 grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-96 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
        ))}
      </div>
      {offline && <p className="mt-4 text-[15px] text-muted">Can&apos;t reach the server. Retrying every 2 seconds.</p>}
    </div>
  );
}
