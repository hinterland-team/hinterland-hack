"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import type {
  Doctor,
  HelpRequest,
  RobotCommand,
  RobotStatus,
  Stats,
  VisitStatus,
  VisitWithQueue,
} from "@/lib/types";
import { WAITING_STATUSES, firstEventAt } from "@/lib/types";
import {
  BOARD_COLUMNS,
  COMMAND_STATE_META,
  PAGE,
  STATUS_META,
  api,
  clockTime,
  commandState,
  commandText,
  duration,
  midnightIso,
  minutes,
  queueText,
  robotLabel,
  robotStateLabel,
  seenAgo,
} from "@/lib/ui";
import { Button, Dot } from "./components/button";
import { Mascot } from "./components/mascot";

const ROBOT = "nova";

type BoardData = {
  visits: VisitWithQueue[];
  doctors: Doctor[];
  stats: Stats;
  help: HelpRequest[];
  robot: RobotStatus;
  commands: RobotCommand[];
};

type Actions = {
  call: (id: string, again?: boolean) => Promise<void>;
  status: (id: string, status: VisitStatus) => Promise<void>;
  assign: (id: string, doctorId: string) => Promise<void>;
};

const LANE_GRID = "board:grid-cols-[224px_repeat(6,minmax(0,1fr))] board:gap-x-4";
const LONG_WAIT = 15 * 60;

export function Board({ practiceChannel, homeSpot }: { practiceChannel: boolean; homeSpot: string }) {
  const [data, setData] = useState<BoardData | null>(null);
  const [offline, setOffline] = useState(false);
  const [loads, setLoads] = useState(0);
  const [now, setNow] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const since = encodeURIComponent(midnightIso());
    try {
      const [visits, doctors, stats, help, robot, commands] = await Promise.all([
        api<VisitWithQueue[]>(`/api/visits?since=${since}`),
        api<Doctor[]>("/api/doctors"),
        api<Stats>(`/api/stats?since=${since}`),
        api<HelpRequest[]>("/api/help?open=1"),
        api<RobotStatus>(`/api/robot/status?robot=${ROBOT}`),
        api<RobotCommand[]>(`/api/robot/commands?robot=${ROBOT}&limit=5`),
      ]);
      setData({ visits, doctors, stats, help, robot, commands });
      setOffline(false);
      setLoads((n) => n + 1);
    } catch {
      setOffline(true);
    }
  }, []);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      await load();
      if (!stopped) timer = setTimeout(tick, 2000);
    };
    tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [load]);

  useEffect(() => {
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const run = useCallback(
    async (fn: () => Promise<unknown>) => {
      try {
        await fn();
        setActionError(null);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : "That did not work");
      }
      await load();
    },
    [load],
  );

  const actions: Actions = {
    call: (id, again = false) =>
      run(() => api(`/api/visits/${id}/call`, { method: "POST", body: JSON.stringify({ again }) })),
    status: (id, status) => run(() => api(`/api/visits/${id}`, { method: "PATCH", body: JSON.stringify({ status }) })),
    assign: (id, doctorId) => run(() => api(`/api/visits/${id}`, { method: "PATCH", body: JSON.stringify({ doctorId }) })),
  };

  const helpDone = (id: string) =>
    run(() => api(`/api/help/${id}`, { method: "PATCH", body: JSON.stringify({ status: "done" }) }));

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-8 pb-6">
        <div>
          <h1 className="text-2xl font-semibold">Today at reception</h1>
          <p className="text-sm text-muted">
            {now
              ? `${new Date(now).toLocaleDateString([], { weekday: "long", day: "numeric", month: "long" })}, ${clockTime(new Date(now).toISOString())}`
              : " "}
          </p>
        </div>
        <LiveBadge offline={offline} ready={data !== null} />
      </div>

      {actionError && (
        <p role="status" className="mb-6 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-sm text-danger">
          {actionError}
        </p>
      )}

      {data && data.help.length > 0 && (
        <div className="mb-6 flex flex-col gap-3">
          {data.help.map((h) => (
            <HelpAlert key={h.id} help={h} onDone={() => helpDone(h.id)} />
          ))}
        </div>
      )}

      {data && now !== null ? (
        <>
          <StatsStrip stats={data.stats} />
          <RobotPanel
            status={data.robot}
            commands={data.commands}
            doctors={data.doctors}
            homeSpot={homeSpot}
            now={now}
            reload={load}
          />
          {data.doctors.length === 0 && data.visits.length === 0 ? (
            <EmptyBoard />
          ) : (
            <Lanes data={data} now={now} animate={loads > 1} practiceChannel={practiceChannel} actions={actions} />
          )}
        </>
      ) : (
        <p className="py-16 text-base text-muted">{offline ? "Can't reach the server." : "Loading the board"}</p>
      )}
    </main>
  );
}

function LiveBadge({ offline, ready }: { offline: boolean; ready: boolean }) {
  if (offline) {
    return (
      <span className="inline-flex items-center gap-2 rounded-full bg-danger-soft px-3 py-1 text-sm font-medium text-danger">
        <Dot className="bg-danger" /> Can&apos;t reach the server
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-2 rounded-full bg-ok-soft px-3 py-1 text-sm font-medium text-ok">
      <Dot className="bg-ok" pulse={ready} /> Live, updates every 2 s
    </span>
  );
}

function HelpAlert({ help, onDone }: { help: HelpRequest; onDone: () => void }) {
  const urgent = help.urgency === "urgent";
  return (
    <div
      role="alert"
      className={`flex flex-wrap items-center gap-4 rounded-2xl border px-6 py-4 ${
        urgent ? "border-danger bg-danger-soft" : "border-primary bg-primary-soft"
      }`}
    >
      <BellIcon className={urgent ? "text-danger" : "text-primary"} />
      <div className="min-w-60 flex-1">
        <p className={`text-base font-semibold ${urgent ? "text-danger" : "text-primary-ink"}`}>
          {urgent ? "Urgent: " : ""}
          {robotLabel(help.robot)} needs a person at reception
        </p>
        <p className="text-base">{help.summary}</p>
        <p className="text-sm text-muted">
          {clockTime(help.createdAt)}, {help.notified ? "sent to Teams" : "not sent to Teams"}
        </p>
      </div>
      <Button kind={urgent ? "danger" : "primary"} onClick={onDone}>
        I&apos;m on it
      </Button>
    </div>
  );
}

function BellIcon({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={`size-6 shrink-0 ${className}`} fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  );
}

// three cells, so the dividers line up with the robot panel below
function StatsStrip({ stats }: { stats: Stats }) {
  const cells = [
    { label: "Check-ins today", value: String(stats.checkInsToday), note: `${stats.doneToday} done so far` },
    {
      label: "Average wait",
      value: stats.avgWaitSeconds === null ? "–" : minutes(stats.avgWaitSeconds),
      note: "from check-in to call",
    },
    {
      label: "Waiting now",
      value: String(stats.waitingNow),
      note: `${stats.withDoctorNow} with a doctor`,
    },
  ];
  const borders = ["", "border-l", "col-span-2 border-t lg:col-span-1 lg:border-t-0 lg:border-l"];

  return (
    <section aria-label="Today in numbers" className="grid grid-cols-2 rounded-2xl border border-line bg-surface lg:grid-cols-3">
      {cells.map((c, i) => (
        <div key={c.label} className={`border-line p-4 sm:p-6 ${borders[i]}`}>
          <p className="text-sm text-muted">{c.label}</p>
          <p className="text-2xl font-semibold tabular-nums sm:text-display">{c.value}</p>
          <p className="text-sm text-muted">{c.note}</p>
        </div>
      ))}
    </section>
  );
}

const CELL = "border-line p-4 sm:p-6";
const NEXT_CELL = `${CELL} border-t lg:border-t-0 lg:border-l`;
const INPUT = "min-w-0 flex-1 rounded-lg border border-line-strong bg-surface px-3 py-2 text-base placeholder:text-muted";

function RobotPanel({
  status,
  commands,
  doctors,
  homeSpot,
  now,
  reload,
}: {
  status: RobotStatus;
  commands: RobotCommand[];
  doctors: Doctor[];
  homeSpot: string;
  now: number;
  reload: () => Promise<void>;
}) {
  const name = robotLabel(status.robot);
  const spots = [...new Set([homeSpot, ...doctors.filter((d) => d.active).map((d) => d.spot)])];

  return (
    <section aria-label={`${name}, the robot`} className="mt-4 grid rounded-2xl border border-line bg-surface lg:grid-cols-3">
      <RobotStatusCell status={status} name={name} now={now} />
      <div className={NEXT_CELL}>
        <RobotControls robot={status.robot} name={name} online={status.online} spots={spots} reload={reload} />
      </div>
      <div className={NEXT_CELL}>
        <p className="text-sm text-muted">Last commands</p>
        {commands.length === 0 ? (
          <p className="mt-2 text-sm text-muted">Nothing sent yet. What you send shows up here with its state.</p>
        ) : (
          <ol className="mt-2 flex flex-col gap-2">
            {commands.map((c) => {
              const state = COMMAND_STATE_META[commandState(c)];
              return (
                <li key={c.id} className="grid grid-cols-[40px_minmax(0,1fr)_auto] items-baseline gap-2 text-sm">
                  <span className="text-muted tabular-nums">{clockTime(c.createdAt)}</span>
                  <span className="break-words">{commandText(c)}</span>
                  <span className={`rounded-full px-2 py-1 text-xs font-medium ${state.tone}`}>{state.label}</span>
                </li>
              );
            })}
          </ol>
        )}
      </div>
    </section>
  );
}

function RobotStatusCell({ status, name, now }: { status: RobotStatus; name: string; now: number }) {
  const seen = status.lastSeenAt;
  const reported = [status.state, status.battery, status.position, status.goal].some((v) => v !== null && v !== undefined);

  return (
    <div className={CELL}>
      <p className="text-sm text-muted">{name}</p>
      <p className="flex items-center gap-3 text-2xl font-semibold sm:text-display">
        <Dot className={`size-3 ${status.online ? "bg-ok" : "bg-muted"}`} pulse={status.online} />
        {status.online ? "Online" : seen ? "Offline" : "Not connected"}
      </p>
      <p className="text-sm text-muted tabular-nums">
        {seen ? `last seen ${seenAgo(seen, now)}` : `${name} has not reached the server yet`}
      </p>
      {seen &&
        (reported ? (
          <dl
            className={`mt-4 grid grid-cols-[64px_minmax(0,1fr)] items-baseline gap-x-3 gap-y-2 text-sm ${
              status.online ? "" : "text-muted"
            }`}
          >
            <dt className="text-muted">Doing</dt>
            <dd className="break-words">{status.goal ?? "Nothing right now"}</dd>
            <dt className="text-muted">Battery</dt>
            <dd>
              <Battery level={status.battery ?? null} charging={status.charging === true} stale={!status.online} />
            </dd>
            <dt className="text-muted">State</dt>
            <dd className={status.state === "error" ? "text-warn" : ""}>
              {status.state ? robotStateLabel(status.state) : "Unknown"}
            </dd>
            <dt className="text-muted">Position</dt>
            <dd className="break-words">{status.position ?? "Unknown"}</dd>
            {status.detail && (
              <>
                <dt className="text-muted">Note</dt>
                <dd className="break-words">{status.detail}</dd>
              </>
            )}
          </dl>
        ) : (
          <p className="mt-4 text-sm text-muted">No status report yet. {name} sends one every 15 s.</p>
        ))}
    </div>
  );
}

// stale = offline, the last known level in grey
function Battery({ level, charging, stale }: { level: number | null; charging: boolean; stale: boolean }) {
  if (level === null) return <>{charging ? "Unknown, charging" : "Unknown"}</>;
  const tone = stale ? "bg-muted" : level < 20 ? "bg-danger" : level < 50 ? "bg-warn" : "bg-ok";
  return (
    <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
      <span
        role="meter"
        aria-label="Battery"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={level}
        className="block h-2 w-24 overflow-hidden rounded-full bg-line"
      >
        <span className={`block h-full rounded-full ${tone}`} style={{ width: `${level}%` }} />
      </span>
      <span className="tabular-nums">
        {level}%{charging ? ", charging" : ""}
      </span>
    </span>
  );
}

function RobotControls({
  robot,
  name,
  online,
  spots,
  reload,
}: {
  robot: string;
  name: string;
  online: boolean;
  spots: string[];
  reload: () => Promise<void>;
}) {
  const [say, setSay] = useState("");
  const [spot, setSpot] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async (body: { type: "say"; text: string } | { type: "go_to"; spot: string }) => {
    setBusy(true);
    setError(null);
    try {
      await api("/api/robot/commands", { method: "POST", body: JSON.stringify({ robot, ...body }) });
      if (body.type === "say") setSay("");
      else setSpot("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "That did not work");
    }
    setBusy(false);
    await reload();
  };

  return (
    <div className="flex flex-col gap-4">
      <form
        className="flex flex-col gap-1"
        onSubmit={(e) => {
          e.preventDefault();
          send({ type: "say", text: say });
        }}
      >
        <label htmlFor="robot-say" className="text-sm font-medium">
          Say something
        </label>
        <div className="flex gap-2">
          <input
            id="robot-say"
            className={INPUT}
            value={say}
            maxLength={300}
            onChange={(e) => setSay(e.target.value)}
            placeholder="Please take a seat"
            autoComplete="off"
          />
          <Button kind="primary" type="submit" className="min-w-16" disabled={busy || !say.trim()}>
            Say
          </Button>
        </div>
      </form>
      <form
        className="flex flex-col gap-1"
        onSubmit={(e) => {
          e.preventDefault();
          send({ type: "go_to", spot });
        }}
      >
        <label htmlFor="robot-spot" className="text-sm font-medium">
          Send to
        </label>
        <div className="flex gap-2">
          <input
            id="robot-spot"
            className={INPUT}
            list="robot-spots"
            value={spot}
            maxLength={80}
            onChange={(e) => setSpot(e.target.value)}
            placeholder="Pick or type a map spot"
            autoComplete="off"
          />
          <datalist id="robot-spots">
            {spots.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
          <Button kind="primary" type="submit" className="min-w-16" disabled={busy || !spot.trim()}>
            Send
          </Button>
        </div>
      </form>
      {error ? (
        <p className="text-sm text-danger">{error}</p>
      ) : (
        <p className="text-xs text-muted">
          {online ? `${name} picks it up within a few seconds.` : `${name} is offline and gets it when it is back.`}
        </p>
      )}
    </div>
  );
}

function EmptyBoard() {
  return (
    <section className="flex flex-col items-start gap-4 py-16">
      <Mascot size={96} />
      <h2 className="text-2xl font-semibold">Nova is ready at reception</h2>
      <p className="max-w-xl text-base text-muted">
        Add the doctors of your practice first, so Nova knows whose patients it checks in and where to walk them.
      </p>
      <div className="flex flex-wrap gap-3">
        <Link href="/doctors" className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover">
          Add doctors
        </Link>
        <Link href="/simulate" className="rounded-lg border border-line-strong px-4 py-2 text-sm font-medium hover:bg-subtle">
          Try the simulator
        </Link>
      </div>
    </section>
  );
}

type Lane = { key: string; doctor: Doctor | null; title: string; visits: VisitWithQueue[] };

function Lanes({
  data,
  now,
  animate,
  practiceChannel,
  actions,
}: {
  data: BoardData;
  now: number;
  animate: boolean;
  practiceChannel: boolean;
  actions: Actions;
}) {
  const shown = data.visits.filter((v) => v.status !== "cancelled");
  const known = new Set(data.doctors.map((d) => d.id));
  const lanes: Lane[] = [];

  const unmatched = shown.filter((v) => !v.doctorId || !known.has(v.doctorId));
  if (unmatched.length) lanes.push({ key: "unknown", doctor: null, title: "Doctor not found", visits: unmatched });
  for (const d of data.doctors) {
    const visits = shown.filter((v) => v.doctorId === d.id);
    if (d.active || visits.length) lanes.push({ key: d.id, doctor: d, title: d.name, visits });
  }

  const activeDoctors = data.doctors.filter((d) => d.active);
  const counts = Object.fromEntries(BOARD_COLUMNS.map((s) => [s, shown.filter((v) => v.status === s).length]));
  const anyone = shown.length > 0;

  return (
    <section aria-label="Patients by doctor" className="mt-8">
      <div className={`hidden pb-3 board:grid ${LANE_GRID}`}>
        <p className="text-sm font-medium text-muted">Doctor</p>
        {BOARD_COLUMNS.map((s) => (
          <p key={s} className="flex items-center gap-2 text-sm font-medium">
            <Dot className={STATUS_META[s].dot} />
            {s === "done" ? "Done today" : STATUS_META[s].label}
            <span className="text-muted tabular-nums">{counts[s]}</span>
          </p>
        ))}
      </div>

      {!anyone && (
        <p className="border-t border-line py-4 text-sm text-muted">
          No patients yet today. When Nova checks someone in, they show up here.
        </p>
      )}

      {lanes.map((lane) => (
        <div
          key={lane.key}
          className={`grid grid-cols-1 gap-3 border-t border-line py-6 sm:grid-cols-[repeat(auto-fill,minmax(288px,1fr))] board:gap-y-0 board:py-4 ${LANE_GRID}`}
        >
          <LaneLabel lane={lane} practiceChannel={practiceChannel} />
          {lane.visits.length === 0 && <p className="col-span-full text-sm text-muted board:hidden">No patients right now.</p>}
          {BOARD_COLUMNS.map((s) => {
            const cell = lane.visits
              .filter((v) => v.status === s)
              .sort((a, b) => (s === "done" ? b.updatedAt.localeCompare(a.updatedAt) : a.createdAt.localeCompare(b.createdAt)));
            return (
              <div key={s} className="contents board:flex board:flex-col board:gap-2">
                {cell.map((v) => (
                  <VisitCard
                    key={v.id}
                    visit={v}
                    now={now}
                    animate={animate}
                    doctors={lane.doctor ? null : activeDoctors}
                    actions={actions}
                  />
                ))}
              </div>
            );
          })}
        </div>
      ))}
    </section>
  );
}

function LaneLabel({ lane, practiceChannel }: { lane: Lane; practiceChannel: boolean }) {
  const waiting = lane.visits.filter((v) => WAITING_STATUSES.includes(v.status)).length;
  const withDoctor = lane.visits.filter((v) => v.status === "with_doctor").length;
  const d = lane.doctor;
  const teams = d ? (d.teamsWebhookUrl ? "own Teams channel" : practiceChannel ? "practice Teams channel" : "no Teams channel") : null;

  return (
    <div className="col-span-full flex flex-wrap items-baseline gap-x-4 gap-y-1 board:col-span-1 board:flex-col board:items-start board:pr-4">
      <h2 className={`text-lg font-semibold ${d ? "" : "text-warn"}`}>
        {lane.title}
        {d && !d.active && <span className="ml-2 text-sm font-normal text-muted">(inactive)</span>}
      </h2>
      {d ? (
        <>
          <p className="text-sm text-muted">Map spot: {d.spot}</p>
          <p className="text-sm text-muted">
            {waiting} waiting, {withDoctor} with doctor
          </p>
          <p className="text-sm text-muted">About {d.avgConsultMinutes} min per patient</p>
          <p className="text-xs text-muted">Notified via {teams}</p>
        </>
      ) : (
        <p className="text-sm text-muted">Nova could not match the name. Pick the doctor on the card.</p>
      )}
    </div>
  );
}

function VisitCard({
  visit,
  now,
  animate,
  doctors,
  actions,
}: {
  visit: VisitWithQueue;
  now: number;
  animate: boolean;
  doctors: Doctor[] | null;
  actions: Actions;
}) {
  const [flash] = useState(animate);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [pick, setPick] = useState("");

  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    await fn();
    setBusy(false);
    setConfirming(false);
  };

  const robot = robotLabel(visit.robot);
  const created = Date.parse(visit.createdAt);
  const calledAt = firstEventAt(visit, "called");
  const waited = calledAt ? (Date.parse(calledAt) - created) / 1000 : (now - created) / 1000;
  const waiting = WAITING_STATUSES.includes(visit.status);
  const done = visit.status === "done";
  const events = expanded ? visit.events : visit.events.slice(-3);
  const hidden = visit.events.length - events.length;

  // the robot fetched the call but then put the visit back to "called" (it could not walk)
  const callSteps = visit.events.filter((e) => ["called", "called_again", "robot_got_call"].includes(e.type));
  const lastStep = callSteps.at(-1);
  const stalled =
    visit.status === "called" &&
    lastStep?.type === "called" &&
    callSteps.slice(0, -1).some((e) => e.type === "robot_got_call");

  let line: { text: string; tone?: string; pulse?: boolean };
  if (waiting) {
    line = { text: `Waiting ${duration(waited)}`, tone: waited > LONG_WAIT ? "text-warn" : "text-ink" };
  } else if (visit.status === "called") {
    line = stalled
      ? { text: lastStep?.detail ?? `${robot} could not finish the walk`, tone: "text-warn" }
      : lastStep?.type === "robot_got_call"
        ? { text: `${robot} is on the way`, tone: "text-primary-ink", pulse: true }
        : { text: `Waiting for ${robot}`, tone: "text-primary-ink" };
  } else if (visit.status === "escorting") {
    line = { text: `${robot} walks them over`, tone: "text-primary-ink", pulse: true };
  } else if (visit.status === "with_doctor") {
    const since = firstEventAt(visit, "with_doctor");
    line = { text: `With doctor ${duration((now - Date.parse(since ?? visit.updatedAt)) / 1000)}`, tone: "text-ink" };
  } else {
    line = { text: `Done at ${clockTime(visit.updatedAt)}`, tone: "text-muted" };
  }

  return (
    <article className={`flex flex-col gap-3 rounded-xl border border-line bg-surface p-3 ${flash ? "arrive" : ""}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-base font-semibold break-words">{visit.patientName}</h3>
          <p className="text-sm text-muted">
            {visit.appointmentTime ? `Appointment ${visit.appointmentTime}` : "No appointment time"}
            {visit.language ? `, speaks ${visit.language.toUpperCase()}` : ""}
          </p>
        </div>
        <span className="flex shrink-0 items-center gap-2 rounded-full bg-subtle px-2 py-1 text-xs font-medium board:hidden">
          <Dot className={STATUS_META[visit.status].dot} />
          {STATUS_META[visit.status].label}
        </span>
      </div>

      <p className={`flex items-center gap-2 text-sm font-medium tabular-nums ${line.tone ?? ""}`}>
        {line.pulse && <Dot className="bg-st-escorting" pulse />}
        {line.text}
      </p>
      {waiting && visit.queuePosition !== null && (
        <p className="-mt-2 text-sm text-muted tabular-nums">{queueText(visit.queuePosition, visit.estimatedWaitMinutes)}</p>
      )}
      {!waiting && !done && calledAt && <p className="-mt-2 text-sm text-muted tabular-nums">Waited {duration(waited)}</p>}
      {done && calledAt && <p className="-mt-2 text-sm text-muted tabular-nums">Waited {duration(waited)}</p>}

      {!done && (
        <div className="border-t border-line pt-3">
          <ol className="flex flex-col gap-1">
            {events.map((e, i) => (
              <li key={`${e.at}-${i}`} className="grid grid-cols-[40px_minmax(0,1fr)] gap-2 text-xs">
                <span className="text-muted tabular-nums">{clockTime(e.at)}</span>
                <span className="break-words">{e.detail ?? e.type.replace(/_/g, " ")}</span>
              </li>
            ))}
          </ol>
          {visit.events.length > 3 && (
            <button
              type="button"
              onClick={() => setExpanded(!expanded)}
              className="mt-1 text-xs font-medium text-primary hover:underline"
            >
              {expanded ? "Show less" : `Show all ${visit.events.length} steps (${hidden} more)`}
            </button>
          )}
        </div>
      )}

      {doctors && !done && (
        <div className="flex flex-wrap gap-2">
          <label className="w-full text-sm text-warn" htmlFor={`pick-${visit.id}`}>
            {robot} heard &quot;{visit.doctorName}&quot;
          </label>
          <select
            id={`pick-${visit.id}`}
            value={pick}
            onChange={(e) => setPick(e.target.value)}
            className="min-w-0 flex-1 rounded-lg border border-line-strong bg-surface px-3 py-2 text-sm"
          >
            <option value="">Pick the doctor</option>
            {doctors.map((d) => (
              <option key={d.id} value={d.id}>
                {d.name}
              </option>
            ))}
          </select>
          <Button kind="primary" disabled={!pick || busy} onClick={() => act(() => actions.assign(visit.id, pick))}>
            Assign
          </Button>
        </div>
      )}

      {!done &&
        (confirming ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="w-full text-sm">Cancel this visit?</span>
            <Button kind="danger" disabled={busy} onClick={() => act(() => actions.status(visit.id, "cancelled"))}>
              Yes, cancel
            </Button>
            <Button onClick={() => setConfirming(false)}>Keep</Button>
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {waiting && !doctors && (
              <Button kind="primary" disabled={busy} onClick={() => act(() => actions.call(visit.id))}>
                Call in
              </Button>
            )}
            {stalled && (
              <Button kind="primary" disabled={busy} onClick={() => act(() => actions.call(visit.id, true))}>
                Send {robot} again
              </Button>
            )}
            {!waiting && (
              <Button
                kind={visit.status === "with_doctor" ? "primary" : "secondary"}
                disabled={busy}
                onClick={() => act(() => actions.status(visit.id, "done"))}
              >
                Done
              </Button>
            )}
            {visit.status !== "with_doctor" && (
              <Button kind="ghost" disabled={busy} onClick={() => setConfirming(true)}>
                Cancel
              </Button>
            )}
          </div>
        ))}
    </article>
  );
}
