"use client";

import { ChatCircleText, Lightning, Megaphone, NavigationArrow, PaperPlaneRight, Sparkle } from "@phosphor-icons/react";
import { useState } from "react";
import type { Board } from "@/lib/board";
import type { FeedItem, JourneyWithQueue, Place, RobotStatus } from "@/lib/types";
import { ROBOT, clockTime, commandState, commandText, post, robotLabel, seenAgo, stageOf } from "@/lib/ui";
import { Button, Dot, Switch } from "../components/button";
import { TicketBadge } from "../components/journey-bits";
import { Mascot } from "../components/mascot";
import { RobotScreen } from "../components/robot-screen";
import type { Run } from "../components/journey-actions";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";

export function LivePanel({ data, now, run, busy }: { data: Board; now: number; run: Run; busy: string | null }) {
  const status = data.robot;
  const current = data.journeys.find((j) => j.id === data.currentJourneyId);
  const name = robotLabel(status.robot);

  return (
    <section
      aria-labelledby="live-title"
      className="@container rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Mascot size={40} />
        <div className="min-w-0 flex-1">
          <h2 id="live-title" className="text-xl font-semibold tracking-tight">
            {name} live
          </h2>
          <OnlineLine status={status} now={now} />
        </div>
        <Battery status={status} />
      </div>

      <div className="mt-4 grid gap-5 @2xl:grid-cols-2">
        <div className="min-w-0">
          <RobotScreen status={status} />
          <dl className="mt-3 grid grid-cols-[72px_minmax(0,1fr)] gap-x-3 gap-y-1 text-[15px]">
            <dt className="text-muted">Doing</dt>
            <dd className="break-words">{status.goal ?? (status.online ? "Nothing right now" : "Unknown")}</dd>
            <dt className="text-muted">Where</dt>
            <dd className="break-words">{status.position ?? "Unknown"}</dd>
          </dl>
          {current && <Timeline journey={current} now={now} />}
        </div>
        <div className="min-w-0">
          <Conversation items={data.feed.items} show={data.feed.showPatientText} online={status.online} />
        </div>
      </div>

      <Controls status={status} places={data.places} showPatientText={data.feed.showPatientText} run={run} busy={busy} />
      <Recent commands={data.commands} />
    </section>
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

function Timeline({ journey, now }: { journey: JourneyWithQueue; now: number }) {
  const stage = stageOf(journey, now);
  const events = journey.events.filter((e) => !e.type.startsWith("teams_")).slice(-5);
  return (
    <div className="mt-4 rounded-xl border border-line p-3">
      <div className="flex items-center gap-3">
        <TicketBadge ticket={journey.ticket} stage={stage} />
        <div className="min-w-0">
          <p className="font-semibold">{stage.title}</p>
          <p className="text-sm text-muted">{stage.detail}</p>
        </div>
      </div>
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

function Conversation({ items, show, online }: { items: FeedItem[]; show: boolean; online: boolean }) {
  const [all, setAll] = useState(false);
  // the feed comes newest first; read top to bottom like a chat
  const list = items.slice(0, all ? 30 : 7).reverse();
  return (
    <div>
      <h3 className="flex items-center gap-2 text-sm font-semibold">
        <ChatCircleText size={18} aria-hidden /> Conversation
      </h3>
      {list.length === 0 ? (
        <p className="mt-2 rounded-xl bg-subtle p-4 text-[15px] text-muted">
          {online ? "Nothing said yet." : "Nova is offline."} When Nova talks with a patient, its lines and what it understood
          show up here.
          {show ? " The demo switch is on, so patients' own words show too." : " Patients' own words stay on the robot."}
        </p>
      ) : (
        <ol className="mt-2 grid gap-2" aria-live="polite">
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
    </div>
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
  const nova = item.who === "nova";
  return (
    <li className={`rise flex flex-col ${nova ? "items-start" : "items-end"}`}>
      <span className="mb-0.5 flex items-center gap-2 text-xs font-semibold text-muted">
        {nova ? "Nova" : item.kind === "said" ? "Patient said" : "Patient"} {time}
      </span>
      <span
        className={`max-w-[92%] rounded-2xl px-3 py-2 text-[15px] leading-snug break-words ${
          nova
            ? "rounded-tl-md bg-primary-soft text-ink"
            : item.kind === "intent"
              ? "rounded-tr-md border border-dashed border-line-strong italic text-muted"
              : "rounded-tr-md bg-subtle"
        }`}
      >
        {item.kind === "said" ? `“${item.text}”` : item.text}
      </span>
    </li>
  );
}

function Controls({
  status,
  places,
  showPatientText,
  run,
  busy,
}: {
  status: RobotStatus;
  places: Place[];
  showPatientText: boolean;
  run: Run;
  busy: string | null;
}) {
  const [message, setMessage] = useState("");
  const [spot, setSpot] = useState("");
  const spots = places.filter((p) => p.active);
  const sendText = (type: "ask" | "say") =>
    run(`robot:${type}`, async () => {
      await post("/api/robot/commands", { robot: ROBOT, type, text: message });
      setMessage("");
    });

  return (
    <div className="mt-5 grid gap-4 border-t border-line pt-5">
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (message.trim()) sendText("ask");
        }}
      >
        <label htmlFor="tell-nova" className="text-sm font-semibold">
          Tell Nova anything
        </label>
        <input
          id="tell-nova"
          className={`${INPUT} mt-2`}
          value={message}
          maxLength={500}
          onChange={(e) => setMessage(e.target.value)}
          placeholder="Ask in your own words"
          autoComplete="off"
        />
        <div className="mt-2 flex flex-wrap gap-2">
          <Button kind="primary" type="submit" disabled={!message.trim() || busy === "robot:ask"}>
            <Sparkle size={18} aria-hidden /> Tell Nova
          </Button>
          <Button disabled={!message.trim() || busy === "robot:say"} onClick={() => sendText("say")}>
            <Megaphone size={18} aria-hidden /> Say it out loud
          </Button>
        </div>
        <p className="mt-2 text-sm text-muted">
          Tell Nova: its AI decides what to do. Say it out loud: Nova repeats your words exactly.
          {!status.online && " Nova is offline and gets it when it is back."}
        </p>
      </form>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (spot)
            run("robot:go", () => post("/api/robot/commands", { robot: ROBOT, type: "go_to", spot }).then(() => setSpot("")));
        }}
      >
        <label htmlFor="send-to" className="text-sm font-semibold">
          Send Nova to
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

      <div className="rounded-xl border border-line px-4">
        <Switch
          id="patient-text"
          checked={showPatientText}
          onChange={(v) => run("robot:demo", () => post("/api/robot/feed", { showPatientText: v }, "PATCH"))}
          label="Show what patients say (demo)"
          description={
            showPatientText
              ? "On: patients' words appear in the conversation. Turn off after the demo."
              : "Off: only what Nova understood, like “asked to slow down”."
          }
        />
      </div>
    </div>
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
  const label = { queued: "Waiting", delivered: "Nova has it", done: "Done", cancelled: "Cancelled" };
  return (
    <div className="mt-5 border-t border-line pt-4">
      <h3 className="text-sm font-semibold">Sent to Nova</h3>
      <ol className="mt-2 grid gap-2">
        {commands.slice(0, 4).map((c) => {
          const s = commandState(c);
          return (
            <li key={c.id} className="grid grid-cols-[44px_minmax(0,1fr)_auto] items-baseline gap-2 text-sm">
              <span className="text-muted tabular">{clockTime(c.createdAt)}</span>
              <span className="break-words">{commandText(c)}</span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${tone[s]}`}>{label[s]}</span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
