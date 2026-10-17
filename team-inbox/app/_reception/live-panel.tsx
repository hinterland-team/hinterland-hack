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
  Stack,
  TerminalWindow,
  WarningOctagon,
} from "@phosphor-icons/react";
import Link from "next/link";
import { memo, useState } from "react";
import type { Board } from "@/lib/board";
import { isNotifyEvent } from "@/lib/channel-types";
import type { FeedItem, FloorInfo, JourneyWithQueue, Place, RobotStatus, WalkAction } from "@/lib/types";
import { currentLeg } from "@/lib/types";
import { ROBOT, SIM_ROBOT, WALK_ACTION_LABEL, clockTime, commandState, commandText, isSimRobot, post, robotLabel, seenAgo, stageOf } from "@/lib/ui";
import { Button, Dot, Segmented, Switch } from "../components/button";
import type { Run } from "../components/journey-actions";
import { TicketBadge } from "../components/journey-bits";
import { Mascot } from "../components/mascot";
import { WAITING_TEXT, partTitle } from "@/lib/demo-script";
import { MODE_INFO } from "@/lib/modes";
import { modeLine, useRobotMode } from "../components/robot-mode";
import { RobotScreen } from "../components/robot-screen";
import { SimBadge } from "../components/sim-badge";
import { useNow } from "../components/use-poll";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";
const PANEL = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6";


// the robot right now: online, battery, mode, floor, errors, out of service, its screen and the walk it is on
export const RobotCard = memo(function RobotCard({
  robot: status,
  journeys,
  currentJourneyId,
  logErrors,
  places,
  floors,
  run,
  busy,
  pick,
}: {
  robot: RobotStatus;
  journeys: JourneyWithQueue[];
  currentJourneyId: string | null;
  logErrors: Board["logErrors"];
  places: Place[];
  floors?: FloorInfo[];
  run: Run;
  busy: string | null;
  // shown while a simulation runs: switch the card between the real otto and virtual otto
  pick?: (robot: string) => void;
}) {
  const current = journeys.find((j) => j.id === currentJourneyId);
  const name = robotLabel(status.robot);
  const sim = isSimRobot(status.robot);
  const broken = status.online && status.state === "error";
  const mode = useRobotMode();
  const modeText = sim ? null : modeLine(mode);

  return (
    <section aria-labelledby="robot-title" className={PANEL}>
      {pick && (
        <div className="mb-4">
          <Segmented
            label="Which Otto"
            value={sim ? SIM_ROBOT : ROBOT}
            onChange={(v) => pick(v)}
            options={[
              { value: ROBOT, label: "Otto" },
              { value: SIM_ROBOT, label: "Virtual Otto" },
            ]}
          />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <Mascot size={40} />
        <div className="min-w-0 flex-1">
          <h2 id="robot-title" className="flex flex-wrap items-center gap-2 text-xl font-semibold tracking-tight">
            {name} live {sim && <SimBadge />}
          </h2>
          <OnlineLine status={status} />
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
              Not started from the home screen: start Otto&apos;s app (Nova Clinic) again from the robot&apos;s home screen.
              Emergency stop, low battery or an update: check the robot.
            </p>
          </div>
        </div>
      )}

      <div className="mt-4">
        <RobotScreen status={status} />
      </div>
      <dl className="mt-3 grid grid-cols-[72px_minmax(0,1fr)] gap-x-3 gap-y-1 text-[15px]">
        {modeText && (
          <>
            <dt className="text-muted">Mode</dt>
            <dd className="break-words">
              {name}: {modeText}
            </dd>
          </>
        )}
        {status.demo?.running && (
          <>
            <dt className="text-muted">Demo</dt>
            <dd className="break-words">
              {status.demo.part
                ? `${partTitle(status.demo.part)}${status.demo.step && status.demo.total ? `, step ${status.demo.step} of ${status.demo.total}` : ""}`
                : "Between parts"}
              {status.demo.waitingFor ? `. ${WAITING_TEXT[status.demo.waitingFor]}` : ""}
              {status.demo.runs > 0 ? `. ${status.demo.runs} ${status.demo.runs === 1 ? "run" : "runs"} done` : ""}.{" "}
              <Link href={sim ? "/demo?robot=otto-sim" : "/demo"} className="font-semibold text-primary hover:underline">
                Demo page
              </Link>
            </dd>
          </>
        )}
        <dt className="text-muted">Doing</dt>
        <dd className="break-words">{status.goal ?? (status.online ? "Nothing right now" : "Unknown")}</dd>
        <dt className="text-muted">Where</dt>
        <dd className="break-words">{status.position ?? "Unknown"}</dd>
        {status.mapName && (
          <>
            <dt className="text-muted">Floor</dt>
            <dd className="break-words">
              {status.mapName}
              {status.localised === false && <span className="text-warn">, not sure where it stands yet</span>}
            </dd>
          </>
        )}
        {status.server && (
          <>
            <dt className="text-muted">Server</dt>
            <dd className="break-all">{status.server.replace(/^https?:\/\//, "").replace(/\/+$/, "")}</dd>
          </>
        )}
        {status.version && (
          <>
            <dt className="text-muted">App</dt>
            <dd className="break-words">{sim ? "Simulation in this dashboard" : `Nova Clinic ${status.version}`}</dd>
          </>
        )}
      </dl>
      {!sim && floors && floors.length > 1 && <FloorSwitch floors={floors} places={places} walking={Boolean(current && ["pickup", "walking"].includes(currentLeg(current).status))} run={run} busy={busy} />}
      {!sim && <ErrorLink count={logErrors.count} lastAt={logErrors.lastAt} />}
      {current && <Timeline journey={current} run={run} busy={busy} />}
    </section>
  );
});

// "Switch Otto to <map>" + "Otto is standing at <spot>" -> switch_map (robot-setup.md section 2)
export function FloorSwitch({
  floors,
  places,
  walking,
  run,
  busy,
  initial,
}: {
  floors: FloorInfo[];
  places: Place[];
  walking: boolean;
  run: Run;
  busy: string | null;
  // the map page: open right away for the floor shown there
  initial?: string;
}) {
  const others = floors.filter((f) => !f.current);
  const [open, setOpen] = useState(Boolean(initial));
  const [map, setMap] = useState(initial ?? others[0]?.name ?? "");
  const [spot, setSpot] = useState("");
  const [sent, setSent] = useState<string | null>(null);
  const floor = floors.find((f) => f.name === map);
  // where otto can stand on that map: the spots it reported there, and the places set up on that floor
  const spots = [...new Set([...(floor?.spots ?? []), ...places.filter((p) => p.mapName === map).map((p) => p.spot)])];
  if (!others.length) return null;
  if (!open) {
    return (
      <div className="mt-3">
        <Button size="sm" onClick={() => setOpen(true)}>
          <Stack size={16} aria-hidden /> Switch floor
        </Button>
        {sent && (
          <p className="mt-2 text-sm text-ok" role="status">
            {sent}
          </p>
        )}
      </div>
    );
  }
  return (
    <form
      className={initial ? "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5" : "mt-4 rounded-xl border border-line p-3 sm:p-4"}
      onSubmit={(e) => {
        e.preventDefault();
        if (map && spot)
          run("robot:floor", () =>
            post("/api/robot/commands", { robot: ROBOT, type: "switch_map", mapName: map, spot }).then(() => {
              setSent(`Sent. Otto switches to ${map} and finds where it stands at ${spot}. The robot card shows when it is done.`);
              if (!initial) setOpen(false);
            }),
          );
      }}
    >
      <p className="text-[15px] font-semibold">Switch Otto to another floor</p>
      <p className="mt-1 text-sm text-muted">Put Otto on that floor first (elevator), then tell it where it stands so it can find its way.</p>
      <label htmlFor="floor-map" className="mt-3 block text-sm font-semibold">
        Switch Otto to
      </label>
      <select id="floor-map" className={`${INPUT} mt-1`} value={map} onChange={(e) => (setMap(e.target.value), setSpot(""))}>
        {others.map((f) => (
          <option key={f.name} value={f.name}>
            {f.name}
          </option>
        ))}
      </select>
      <label htmlFor="floor-spot" className="mt-3 block text-sm font-semibold">
        Otto is standing at
      </label>
      {spots.length ? (
        <select id="floor-spot" className={`${INPUT} mt-1`} value={spot} onChange={(e) => setSpot(e.target.value)}>
          <option value="">Pick a spot on {map}</option>
          {spots.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      ) : (
        <input id="floor-spot" className={`${INPUT} mt-1`} value={spot} onChange={(e) => setSpot(e.target.value)} placeholder="The spot name on that map" />
      )}
      {walking && <p className="mt-2 text-sm font-medium text-warn">Otto is walking with a patient. Switch the floor after the walk.</p>}
      {sent && (
        <p className="mt-2 text-sm text-ok" role="status">
          {sent}
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <Button kind="primary" type="submit" disabled={!map || !spot || walking || busy === "robot:floor"}>
          {busy === "robot:floor" ? "Sending" : `Switch Otto to ${map}`}
        </Button>
        {!initial && <Button onClick={() => setOpen(false)}>Cancel</Button>}
      </div>
    </form>
  );
}

function ErrorLink({ count, lastAt }: { count: number; lastAt: string | null }) {
  const now = useNow(30_000);
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
        {lastAt && now !== null && <span className="font-normal">, last one {seenAgo(lastAt, now)}</span>}
      </span>
    </Link>
  );
}

function OnlineLine({ status }: { status: RobotStatus }) {
  const now = useNow(1000);
  if (status.online) {
    return (
      <p className="flex items-center gap-2 text-sm font-medium text-ok">
        <Dot className="bg-ok" live />
        <span>
          Online
          <span className="font-normal text-muted">{status.lastSeenAt && now !== null ? `, seen ${seenAgo(status.lastSeenAt, now)}` : ""}</span>
        </span>
      </p>
    );
  }
  return (
    <p className="flex items-center gap-2 text-sm text-muted">
      <Dot className="bg-muted" />
      {status.lastSeenAt && now !== null
        ? `Offline, last seen ${seenAgo(status.lastSeenAt, now)}`
        : isSimRobot(status.robot)
          ? "Virtual Otto is off. Switch it on under Simulate."
          : "Not connected yet. Start Otto's app (Nova Clinic) on the robot."}
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

function Timeline({ journey, run, busy }: { journey: JourneyWithQueue; run: Run; busy: string | null }) {
  const now = useNow(15_000) ?? Date.now();
  const stage = stageOf(journey, now);
  // messages to staff show on the journey's row, the timeline is about the walk
  const all = journey.events;
  const events = all
    .map((e, i) => ({ e, i }))
    .filter(({ e }) => !isNotifyEvent(e.type))
    .slice(-5);
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
                  onClick={() => run(key, () => post("/api/robot/commands", { type: "walk_control", journeyId: journey.id, action }))}
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
        {events.map(({ e, i }) => (
          // the index in the whole list keeps the key when older lines drop out, so lines don't flash again
          <li key={`${i}-${e.at}`} className="rise grid grid-cols-[44px_minmax(0,1fr)] gap-2 text-sm">
            <span className="text-muted tabular">{clockTime(e.at)}</span>
            <span className="break-words">{e.detail ?? e.type.replace(/_/g, " ")}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

export const ConversationCard = memo(function ConversationCard({
  feed,
  online,
  robot,
  run,
}: {
  feed: Board["feed"];
  online: boolean;
  robot: string;
  run: Run;
}) {
  const [all, setAll] = useState(false);
  const { items, showPatientText: show } = feed;
  // the feed comes newest first; read top to bottom like a chat
  const list = items.slice(0, all ? 30 : 7).reverse();
  const name = robotLabel(robot);
  return (
    <section aria-labelledby="talk-title" className={PANEL}>
      <h2 id="talk-title" className="flex items-center gap-2 text-xl font-semibold tracking-tight">
        <ChatCircleText size={22} aria-hidden /> Conversation {isSimRobot(robot) && <SimBadge />}
      </h2>
      {list.length === 0 ? (
        <p className="mt-3 rounded-xl bg-subtle p-4 text-[15px] text-muted">
          {online ? "Nothing said yet." : `${name} is offline.`} When {name} talks with a patient, its lines and what it understood
          show up here.
          {show ? " The demo switch is on, so patients' own words show too." : " Patients' own words stay on the robot."}
        </p>
      ) : (
        <ol className="mt-3 grid gap-2" aria-live="polite">
          {list.map((i) => (
            <FeedLine key={i.id} item={i} name={name} />
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
});

function FeedLine({ item, name }: { item: FeedItem; name: string }) {
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
        {otto ? name : item.kind === "said" ? "Patient said" : "Patient"} {time}
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

export const ControlsCard = memo(function ControlsCard({
  places,
  commands,
  robot: status,
  run,
  busy,
}: {
  places: Place[];
  commands: Board["commands"];
  robot: RobotStatus;
  run: Run;
  busy: string | null;
}) {
  const [message, setMessage] = useState("");
  const [spot, setSpot] = useState("");
  const spots = places.filter((p) => p.active);
  const online = status.online;
  const robot = status.robot;
  const name = robotLabel(robot);
  // show and calm: the real otto says nothing and refuses words. demo: only the script and Say it out loud
  const modeState = useRobotMode();
  const sim = isSimRobot(robot);
  const demo = sim ? status.demo?.running === true : modeState?.mode === "demo";
  const quiet = !sim && modeState && modeState.mode !== "normal" && modeState.mode !== "demo" ? modeState.mode : null;
  const sendText = (type: "ask" | "say") =>
    run(`robot:${type}`, async () => {
      await post("/api/robot/commands", { robot, type, text: message });
      setMessage("");
    });

  return (
    <section aria-labelledby="tell-title" className={PANEL}>
      <h2 id="tell-title" className="flex flex-wrap items-center gap-2 text-xl font-semibold tracking-tight">
        Tell {name} {isSimRobot(robot) && <SimBadge />}
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
          <Button kind="primary" type="submit" disabled={!message.trim() || busy === "robot:ask" || quiet !== null || demo}>
            <Sparkle size={18} aria-hidden /> Tell {name}
          </Button>
          <Button disabled={!message.trim() || busy === "robot:say" || quiet !== null} onClick={() => sendText("say")}>
            <Megaphone size={18} aria-hidden /> Say it out loud
          </Button>
        </div>
        {quiet ? (
          <p className="mt-2 text-sm font-medium text-warn">
            {name} is in {MODE_INFO[quiet].label} mode and stays silent. Switch to Normal at the top to talk through {name}.
          </p>
        ) : demo ? (
          <p className="mt-2 text-sm font-medium text-warn">
            {name} is in Demo mode: it only says the script&apos;s lines and what you send with Say it out loud.
          </p>
        ) : (
          <p className="mt-2 text-sm text-muted">
            Tell {name}: its AI decides what to do. Say it out loud: {name} repeats your words exactly.
            {!online && ` ${name} is offline and gets it when it is back.`}
          </p>
        )}
      </form>

      <form
        className="mt-5 border-t border-line pt-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (spot) run("robot:go", () => post("/api/robot/commands", { robot, type: "go_to", spot }).then(() => setSpot("")));
        }}
      >
        <label htmlFor="send-to" className="text-sm font-semibold">
          Send {name} to
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
      <Recent commands={commands} name={name} />
    </section>
  );
});

function Recent({ commands, name }: { commands: Board["commands"]; name: string }) {
  if (!commands.length) return null;
  const tone = {
    queued: "bg-subtle text-muted",
    delivered: "bg-primary-soft text-primary-ink",
    done: "bg-ok-soft text-ok",
    cancelled: "bg-subtle text-muted",
    refused: "bg-warn-soft text-warn",
  };
  const label = { queued: "Waiting", delivered: `${name} has it`, done: "Done", cancelled: "Cancelled", refused: "Not done" };
  return (
    <div className="mt-5 border-t border-line pt-4">
      <h3 className="text-sm font-semibold">Sent to {name}</h3>
      <ol className="mt-2 grid gap-2">
        {commands.slice(0, 5).map((c) => {
          const s = commandState(c);
          return (
            <li key={c.id} className="grid grid-cols-[44px_minmax(0,1fr)_auto] items-baseline gap-2 text-sm">
              <span className="text-muted tabular">{clockTime(c.createdAt)}</span>
              <span className="break-words">
                {commandText(c)}
                {c.detail && <span className={`block text-[13px] ${s === "refused" ? "text-warn" : "text-muted"}`}>{c.detail}</span>}
              </span>
              <span className={`rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap ${tone[s]}`}>{label[s]}</span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
