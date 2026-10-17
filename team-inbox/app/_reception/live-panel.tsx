"use client";

import {
  ArrowSquareOut,
  ChatCircleText,
  HandPalm,
  Lightning,
  Megaphone,
  NavigationArrow,
  PaperPlaneRight,
  PersonSimpleRun,
  PersonSimpleWalk,
  Play,
  Sparkle,
  TerminalWindow,
  WarningOctagon,
} from "@phosphor-icons/react";
import Link from "next/link";
import { useState } from "react";
import type { Board } from "@/lib/board";
import type { FeedItem, JourneyWithQueue, Place, RobotStatus, WalkAction } from "@/lib/types";
import { currentLeg } from "@/lib/types";
import { ROBOT, WALK_ACTION_LABEL, clockTime, commandState, commandText, post, robotLabel, seenAgo, stageOf } from "@/lib/ui";
import { Button, Dot, Switch } from "../components/button";
import type { Run } from "../components/journey-actions";
import { TicketBadge } from "../components/journey-bits";
import { Mascot } from "../components/mascot";
import { RobotScreen } from "../components/robot-screen";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";
const PANEL = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6";

// the robot right now: online, battery, errors, out of service, its screen and the walk it is on
export function RobotCard({ data, now, run, busy }: { data: Board; now: number; run: Run; busy: string | null }) {
  const status = data.robot;
  const current = data.journeys.find((j) => j.id === data.currentJourneyId);
  const name = robotLabel(status.robot);
  const broken = status.online && status.state === "error";

  return (
    <section aria-labelledby="robot-title" className={PANEL}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Mascot size={40} />
        <div className="min-w-0 flex-1">
          <h2 id="robot-title" className="text-xl font-semibold tracking-tight">
            {name} live
          </h2>
          <OnlineLine status={status} now={now} />
        </div>
        <Battery status={status} />
      </div>

      {broken && (
        <div role="alert" className="rise mt-4 flex items-start gap-3 rounded-xl border-2 border-danger bg-danger-soft p-4">
          <WarningOctagon size={28} weight="fill" aria-hidden className="shrink-0 text-danger" />
          <div className="min-w-0">
            <p className="text-[17px] font-semibold text-danger">Out of service: {name} can&apos;t drive right now</p>
            <p className="mt-0.5 text-[15px] break-words">{status.detail ?? "The robot reported an error."}</p>
            <p className="mt-1 text-sm text-muted">
              Not started from the home screen: start Nova Clinic again from there. Emergency stop, low battery or an update:
              check the robot.
            </p>
          </div>
        </div>
      )}

      <div className="mt-4">
        <RobotScreen status={status} />
      </div>
      <dl className="mt-3 grid grid-cols-[72px_minmax(0,1fr)] gap-x-3 gap-y-1 text-[15px]">
        <dt className="text-muted">Doing</dt>
        <dd className="break-words">{status.goal ?? (status.online ? "Nothing right now" : "Unknown")}</dd>
        <dt className="text-muted">Where</dt>
        <dd className="break-words">{status.position ?? "Unknown"}</dd>
        {status.server && (
          <>
            <dt className="text-muted">Server</dt>
            <dd className="break-all">{status.server.replace(/^https?:\/\//, "").replace(/\/+$/, "")}</dd>
          </>
        )}
        {status.version && (
          <>
            <dt className="text-muted">App</dt>
            <dd className="break-words">Nova Clinic {status.version}</dd>
          </>
        )}
      </dl>
      <ErrorLink count={data.logErrors.count} lastAt={data.logErrors.lastAt} now={now} />
      {current && <Timeline journey={current} now={now} run={run} busy={busy} />}
    </section>
  );
}

function ErrorLink({ count, lastAt, now }: { count: number; lastAt: string | null; now: number }) {
  if (count === 0) {
    return (
      <Link
        href="/logs"
        className="mt-3 inline-flex h-10 items-center gap-2 rounded-lg px-2 text-sm font-semibold text-muted hover:bg-subtle hover:text-ink"
      >
        <TerminalWindow size={18} aria-hidden /> <span>Logs: no errors in the last hour</span>
      </Link>
    );
  }
  return (
    <Link
      href="/logs?level=error"
      className="mt-3 inline-flex min-h-10 items-center gap-2 rounded-lg bg-danger-soft px-3 py-1.5 text-sm font-semibold text-danger hover:opacity-90"
    >
      <WarningOctagon size={18} weight="fill" aria-hidden />
      <span>
        {count} {count === 1 ? "error" : "errors"} in the last hour
        {lastAt && <span className="font-normal">, last one {seenAgo(lastAt, now)}</span>}
      </span>
    </Link>
  );
}

function OnlineLine({ status, now }: { status: RobotStatus; now: number }) {
  if (status.online) {
    return (
      <p className="flex items-center gap-2 text-sm font-medium text-ok">
        <Dot className="bg-ok" live />
        <span>
          Online
          <span className="font-normal text-muted">{status.lastSeenAt ? `, seen ${seenAgo(status.lastSeenAt, now)}` : ""}</span>
        </span>
      </p>
    );
  }
  return (
    <p className="flex items-center gap-2 text-sm text-muted">
      <Dot className="bg-muted" />
      {status.lastSeenAt
        ? `Offline, last seen ${seenAgo(status.lastSeenAt, now)}`
        : "Not connected yet. Start the Nova Clinic app on the robot."}
    </p>
  );
}

function Battery({ status }: { status: RobotStatus }) {
  const level = status.battery;
  if (level === null || level === undefined) return null;
  const tone = !status.online ? "bg-muted" : level < 20 ? "bg-danger" : level < 40 ? "bg-warn" : "bg-ok";
  return (
    <div className="flex items-center gap-2 text-sm tabular" title="Battery">
      <span
        role="meter"
        aria-label="Battery"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={level}
        className="relative block h-4 w-9 rounded-[5px] border-2 border-line-strong p-px after:absolute after:top-1/2 after:-right-1 after:h-1.5 after:w-0.5 after:-translate-y-1/2 after:rounded-r after:bg-line-strong"
      >
        <span className={`block h-full rounded-[2px] ${tone}`} style={{ width: `${level}%` }} />
      </span>
      <span className="font-semibold">{level}%</span>
      {status.charging && <Lightning size={16} weight="fill" className="text-ok" aria-label="charging" />}
    </div>
  );
}

const WALK_ICONS: Record<WalkAction, typeof HandPalm> = {
  wait: HandPalm,
  resume: Play,
  slower: PersonSimpleWalk,
  faster: PersonSimpleRun,
};

function Timeline({ journey, now, run, busy }: { journey: JourneyWithQueue; now: number; run: Run; busy: string | null }) {
  const stage = stageOf(journey, now);
  const events = journey.events.filter((e) => !e.type.startsWith("teams_") && !e.type.startsWith("email_")).slice(-5);
  const leg = currentLeg(journey);
  const withOtto = leg.status === "pickup" || leg.status === "walking";
  return (
    <div className="mt-4 rounded-xl border border-line p-3 sm:p-4">
      <div className="flex items-center gap-3">
        <TicketBadge ticket={journey.ticket} stage={stage} />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">{stage.title}</p>
          <p className="text-sm text-muted">{stage.detail}</p>
        </div>
      </div>
      {withOtto && (
        <div className="mt-3" role="group" aria-label={`Walk controls for ${journey.ticket}`}>
          <div className="grid grid-cols-4 gap-1.5">
            {(Object.keys(WALK_ICONS) as WalkAction[]).map((action) => {
              const Icon = WALK_ICONS[action];
              const key = `walk:${journey.id}:${action}`;
              return (
                <button
                  key={action}
                  type="button"
                  disabled={busy?.startsWith(`walk:${journey.id}`)}
                  onClick={() =>
                    run(key, () => post("/api/robot/commands", { type: "walk_control", journeyId: journey.id, action }))
                  }
                  className="press flex h-14 flex-col items-center justify-center gap-0.5 rounded-xl border border-line-strong bg-surface text-xs font-semibold text-ink hover:bg-subtle disabled:opacity-45"
                >
                  <Icon size={20} aria-hidden />
                  {busy === key ? "Sending" : WALK_ACTION_LABEL[action]}
                </button>
              );
            })}
          </div>
        </div>
      )}
      {journey.walkCode && (
        <p className="mt-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm text-muted">
          <span>{withOtto ? "The patient has the same buttons on their phone." : "The patient can follow the walk on their phone."}</span>
          <a
            href={`/walk/${journey.walkCode}`}
            target="_blank"
            rel="noreferrer"
            className="-mx-2 flex h-10 items-center gap-1.5 rounded-lg px-2 font-semibold text-primary hover:bg-primary-soft"
          >
            Phone page <ArrowSquareOut size={16} aria-hidden />
          </a>
        </p>
      )}
      <ol className="mt-3 grid gap-1.5">
        {events.map((e, i) => (
          <li key={`${e.at}-${i}`} className="rise grid grid-cols-[44px_minmax(0,1fr)] gap-2 text-sm">
            <span className="text-muted tabular">{clockTime(e.at)}</span>
            <span className="break-words">{e.detail ?? e.type.replace(/_/g, " ")}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

export function ConversationCard({ data, run }: { data: Board; run: Run }) {
  const [all, setAll] = useState(false);
  const { items, showPatientText: show } = data.feed;
  const online = data.robot.online;
  // the feed comes newest first; read top to bottom like a chat
  const list = items.slice(0, all ? 30 : 7).reverse();
  return (
    <section aria-labelledby="talk-title" className={PANEL}>
      <h2 id="talk-title" className="flex items-center gap-2 text-xl font-semibold tracking-tight">
        <ChatCircleText size={22} aria-hidden /> Conversation
      </h2>
      {list.length === 0 ? (
        <p className="mt-3 rounded-xl bg-subtle p-4 text-[15px] text-muted">
          {online ? "Nothing said yet." : "Otto is offline."} When Otto talks with a patient, its lines and what it understood
          show up here.
          {show ? " The demo switch is on, so patients' own words show too." : " Patients' own words stay on the robot."}
        </p>
      ) : (
        <ol className="mt-3 grid gap-2" aria-live="polite">
          {list.map((i) => (
            <FeedLine key={i.id} item={i} />
          ))}
        </ol>
      )}
      {items.length > 7 && (
        <Button kind="ghost" size="sm" className="mt-1" onClick={() => setAll(!all)}>
          {all ? "Show less" : `Show earlier (${Math.min(items.length, 30) - 7})`}
        </Button>
      )}
      <div className="mt-4 rounded-xl border border-line px-4">
        <Switch
          id="patient-text"
          checked={show}
          onChange={(v) => run("robot:demo", () => post("/api/robot/feed", { showPatientText: v }, "PATCH"))}
          label="Show what patients say (demo)"
          description={
            show
              ? "On: patients' words appear here, kept in memory only. Turn off after the demo."
              : "Off: only what Otto understood, like “asked to slow down”."
          }
        />
      </div>
    </section>
  );
}

function FeedLine({ item }: { item: FeedItem }) {
  const time = <span className="text-xs text-muted tabular">{clockTime(item.at)}</span>;
  if (item.kind === "action") {
    return (
      <li className="rise flex items-center gap-2 text-sm text-muted">
        <NavigationArrow size={14} aria-hidden />
        <span className="min-w-0 flex-1 break-words">{item.text}</span>
        {time}
      </li>
    );
  }
  const otto = item.who === "nova";
  return (
    <li className={`rise flex flex-col ${otto ? "items-start" : "items-end"}`}>
      <span className="mb-0.5 flex items-center gap-2 text-xs font-semibold text-muted">
        {otto ? "Otto" : item.kind === "said" ? "Patient said" : "Patient"} {time}
      </span>
      <span
        className={`max-w-[92%] rounded-2xl px-3 py-2 text-[15px] leading-snug break-words ${
          otto
            ? "rounded-tl-md bg-primary-soft text-ink"
            : item.kind === "intent"
              ? "rounded-tr-md border border-dashed border-line-strong text-muted italic"
              : "rounded-tr-md bg-subtle"
        }`}
      >
        {item.kind === "said" ? `“${item.text}”` : item.text}
      </span>
    </li>
  );
}

export function ControlsCard({ data, run, busy }: { data: Board; run: Run; busy: string | null }) {
  const [message, setMessage] = useState("");
  const [spot, setSpot] = useState("");
  const spots = data.places.filter((p: Place) => p.active);
  const online = data.robot.online;
  const sendText = (type: "ask" | "say") =>
    run(`robot:${type}`, async () => {
      await post("/api/robot/commands", { robot: ROBOT, type, text: message });
      setMessage("");
    });

  return (
    <section aria-labelledby="tell-title" className={PANEL}>
      <h2 id="tell-title" className="text-xl font-semibold tracking-tight">
        Tell Otto
      </h2>
      <form
        className="mt-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (message.trim()) sendText("ask");
        }}
      >
        <label htmlFor="tell-otto" className="text-sm font-semibold">
          Anything, in your own words
        </label>
        <input
          id="tell-otto"
          className={`${INPUT} mt-2`}
          value={message}
          maxLength={500}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Ask in your own words"
          autoComplete="off"
        />
        <div className="mt-2 flex flex-wrap gap-2">
          <Button kind="primary" type="submit" disabled={!message.trim() || busy === "robot:ask"}>
            <Sparkle size={18} aria-hidden /> Tell Otto
          </Button>
          <Button disabled={!message.trim() || busy === "robot:say"} onClick={() => sendText("say")}>
            <Megaphone size={18} aria-hidden /> Say it out loud
          </Button>
        </div>
        <p className="mt-2 text-sm text-muted">
          Tell Otto: its AI decides what to do. Say it out loud: Otto repeats your words exactly.
          {!online && " Otto is offline and gets it when it is back."}
        </p>
      </form>

      <form
        className="mt-5 border-t border-line pt-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (spot)
            run("robot:go", () => post("/api/robot/commands", { robot: ROBOT, type: "go_to", spot }).then(() => setSpot("")));
        }}
      >
        <label htmlFor="send-to" className="text-sm font-semibold">
          Send Otto to
        </label>
        <div className="mt-2 flex gap-2">
          <select id="send-to" className={`${INPUT} flex-1`} value={spot} onChange={(e) => setSpot(e.target.value)}>
            <option value="">{spots.length ? "Pick a place" : "Add places first"}</option>
            {spots.map((p) => (
              <option key={p.id} value={p.spot}>
                {p.name}
                {p.room ? `, Room ${p.room}` : ""}
              </option>
            ))}
          </select>
          <Button kind="secondary" size="md" className="h-12" type="submit" disabled={!spot || busy === "robot:go"}>
            <PaperPlaneRight size={18} aria-hidden /> Send
          </Button>
        </div>
      </form>
      <Recent commands={data.commands} />
    </section>
  );
}

function Recent({ commands }: { commands: Board["commands"] }) {
  if (!commands.length) return null;
  const tone = {
    queued: "bg-subtle text-muted",
    delivered: "bg-primary-soft text-primary-ink",
    done: "bg-ok-soft text-ok",
    cancelled: "bg-subtle text-muted",
  };
  const label = { queued: "Waiting", delivered: "Otto has it", done: "Done", cancelled: "Cancelled" };
  return (
    <div className="mt-5 border-t border-line pt-4">
      <h3 className="text-sm font-semibold">Sent to Otto</h3>
      <ol className="mt-2 grid gap-2">
        {commands.slice(0, 5).map((c) => {
          const s = commandState(c);
          return (
            <li key={c.id} className="grid grid-cols-[44px_minmax(0,1fr)_auto] items-baseline gap-2 text-sm">
              <span className="text-muted tabular">{clockTime(c.createdAt)}</span>
              <span className="break-words">{commandText(c)}</span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap ${tone[s]}`}>{label[s]}</span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
