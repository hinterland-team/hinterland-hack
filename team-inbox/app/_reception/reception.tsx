"use client";

import { BellRinging, ThumbsDown, ThumbsUp } from "@phosphor-icons/react";
import type { Board } from "@/lib/board";
import type { HelpRequest } from "@/lib/types";
import { PAGE, clockTime, post, robotLabel } from "@/lib/ui";
import { Button, Dot } from "../components/button";
import { useAction, useBoard } from "../components/use-board";
import { GuidePanel } from "./guide-panel";
import { JourneysPanel } from "./journeys-panel";
import { LivePanel } from "./live-panel";

export function Reception() {
  const { data, offline, now, reload } = useBoard();
  const { run, busy, error, clearError } = useAction(reload);

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-2 pt-6 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Reception</h1>
          <p className="text-[15px] text-muted tabular">
            {now
              ? `${new Date(now).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" })}, ${clockTime(new Date(now).toISOString())}`
              : " "}
          </p>
        </div>
        <LiveBadge offline={offline} ready={data !== null} />
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

      {data && now !== null ? (
        <>
          <Today data={data} />
          <div className="mt-4 grid items-start gap-4 lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] xl:grid-cols-[minmax(0,4fr)_minmax(0,5fr)_minmax(0,4fr)] xl:gap-5">
            <GuidePanel places={data.places} open={data.journeys} onSent={reload} />
            <JourneysPanel journeys={data.journeys} places={data.places} now={now} run={run} busy={busy} />
            <div className="lg:col-span-2 xl:col-span-1">
              <LivePanel data={data} now={now} run={run} busy={busy} />
            </div>
          </div>
        </>
      ) : (
        <Skeleton offline={offline} />
      )}
    </main>
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
          {clockTime(help.createdAt)}, {help.notified ? "also sent to Teams" : "not sent to Teams"}
        </p>
      </div>
      <Button kind={urgent ? "danger" : "primary"} size="lg" disabled={busy} onClick={onDone}>
        I&apos;m on it
      </Button>
    </div>
  );
}

function Today({ data }: { data: Board }) {
  const s = data.stats;
  const cells: { label: string; value: string; note?: React.ReactNode }[] = [
    { label: "Guides today", value: String(s.guides), note: `${s.journeys} patients` },
    { label: "Finished", value: String(s.done), note: `${s.activeNow} still open` },
    {
      label: "Average walk",
      value: s.avgWalkMinutes === null ? "None yet" : s.avgWalkMinutes < 1 ? "Under 1 min" : `${s.avgWalkMinutes} min`,
      note: s.avgWalkMinutes === null ? "From pickup to the room" : "pickup to the room",
    },
    { label: "Lost patients", value: String(s.lostPatients), note: s.lostPatients ? "Nova asked for help" : "Nobody got lost" },
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
}

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
