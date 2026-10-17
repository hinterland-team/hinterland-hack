"use client";

import { useEffect, useRef, useState } from "react";
import type { Doctor, RobotCommand } from "@/lib/types";
import { ROBOT_STATES } from "@/lib/types";
import { PAGE, api, clockTime, queueText, robotLabel, robotStateLabel } from "@/lib/ui";
import { Button, Dot } from "../components/button";
import {
  type RobotCall,
  simCheckIn,
  simCommandDone,
  simHeartbeat,
  simHelp,
  simPoll,
  simVisitStatus,
} from "./actions";

const INPUT = "w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-base placeholder:text-muted";
const PANEL = "flex flex-col gap-4 rounded-2xl border border-line bg-surface p-6";

const DONE_REQUEST = "POST /api/robot/commands/:id/done";
const CALL_STEPS = [
  { label: "Start walking with the patient", request: "PATCH /api/visits/:id with status escorting" },
  { label: "Arrived at the doctor", request: "PATCH /api/visits/:id with status with_doctor" },
  { label: "Back at reception", request: DONE_REQUEST },
];

// what the robot does for each command type, one button per step
function stepsFor(command: RobotCommand) {
  if (command.type === "say") return [{ label: "Said it", request: DONE_REQUEST }];
  if (command.type === "go_to") return [{ label: `Arrived at ${command.spot}`, request: DONE_REQUEST }];
  return CALL_STEPS;
}

export function SimulateClient({ homeSpot }: { homeSpot: string }) {
  const [robot, setRobot] = useState("nova");
  const [doctors, setDoctors] = useState<Doctor[]>([]);
  const [log, setLog] = useState<RobotCall[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [checkIn, setCheckIn] = useState({ patientName: "", doctor: "", appointmentTime: "", language: "" });
  const [checkInReply, setCheckInReply] = useState<string | null>(null);

  const [command, setCommand] = useState<RobotCommand | null>(null);
  const [step, setStep] = useState(0);
  const [inboxNote, setInboxNote] = useState<string | null>(null);
  const [autoPoll, setAutoPoll] = useState(false);

  const [beat, setBeat] = useState({ battery: 80, charging: false, state: "idle", position: homeSpot, goal: "" });
  const [beatReply, setBeatReply] = useState<string | null>(null);
  const [autoBeat, setAutoBeat] = useState(false);

  const [help, setHelp] = useState({ summary: "", urgency: "normal" });
  const [helpReply, setHelpReply] = useState<string | null>(null);

  useEffect(() => {
    api<Doctor[]>("/api/doctors")
      .then(setDoctors)
      .catch(() => setDoctors([]));
  }, []);

  async function call(fn: () => Promise<RobotCall>) {
    setBusy(true);
    setError(null);
    try {
      const c = await fn();
      setLog((l) => [c, ...l].slice(0, 20));
      return c;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Request failed");
      return null;
    } finally {
      setBusy(false);
    }
  }

  async function poll() {
    const c = await call(() => simPoll(robot));
    if (!c) return;
    if (c.status === 200) {
      setCommand(c.response as RobotCommand);
      setStep(0);
      setInboxNote(null);
      setAutoPoll(false);
    } else if (c.status === 204) {
      setInboxNote(
        `Nothing to do at ${clockTime(c.at)}. Call a patient in or send ${robotLabel(robot)} something on the board, then check again.`,
      );
    } else {
      setInboxNote(`The server answered ${c.status}.`);
    }
  }

  const pollRef = useRef(poll);
  pollRef.current = poll;
  useEffect(() => {
    if (!autoPoll) return;
    pollRef.current();
    const t = setInterval(() => pollRef.current(), 3000);
    return () => clearInterval(t);
  }, [autoPoll]);

  async function runStep(i: number) {
    if (!command) return;
    const steps = stepsFor(command);
    const c =
      command.type === "call_patient" && i === 0
        ? await call(() => simVisitStatus(command.visitId, "escorting"))
        : command.type === "call_patient" && i === 1
          ? await call(() => simVisitStatus(command.visitId, "with_doctor"))
          : await call(() => simCommandDone(command.id));
    if (!c || c.status >= 300) return;
    if (i < steps.length - 1) {
      setStep(i + 1);
      return;
    }
    const who = robotLabel(robot);
    setCommand(null);
    setStep(0);
    setInboxNote(
      command.type === "say"
        ? `${who} said it and is free again.`
        : command.type === "go_to"
          ? `${who} is at ${command.spot} and waits there for the next command.`
          : `${who} is back at reception and free again.`,
    );
  }

  async function sendBeat() {
    const c = await call(() => simHeartbeat({ ...beat, robot }));
    if (!c) return;
    const r = c.response as { error?: string } | null;
    setBeatReply(
      c.status === 204
        ? `Sent at ${clockTime(c.at)}. The board shows ${robotLabel(robot)} online for 45 s.`
        : `The server answered ${c.status}${r?.error ? `: ${r.error}` : ""}.`,
    );
  }

  const beatRef = useRef(sendBeat);
  beatRef.current = sendBeat;
  useEffect(() => {
    if (!autoBeat) return;
    beatRef.current();
    const t = setInterval(() => beatRef.current(), 15000);
    return () => clearInterval(t);
  }, [autoBeat]);

  const name = robotLabel(robot || "nova");
  const activeDoctors = doctors.filter((d) => d.active);
  const spots = [...new Set([homeSpot, ...activeDoctors.map((d) => d.spot)])];
  const steps = command ? stepsFor(command) : [];

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-6 pt-8 pb-6">
        <div>
          <h1 className="text-2xl font-semibold">Simulate the robot</h1>
          <p className="max-w-2xl text-base text-muted">
            For demos without the robot. Every button sends the same request as the robot app, and the server adds the
            robot token. Keep the board open in a second window to watch it move.
          </p>
        </div>
        <label className="flex items-center gap-3 text-sm font-medium whitespace-nowrap">
          Robot name
          <input
            className="w-40 rounded-lg border border-line-strong bg-surface px-3 py-2 text-base"
            value={robot}
            onChange={(e) => setRobot(e.target.value)}
          />
        </label>
      </div>

      {error && (
        <p role="status" className="mb-6 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      )}

      <div className="grid items-start gap-6 lg:grid-cols-2 2xl:grid-cols-4">
        <section aria-labelledby="sim-checkin" className={PANEL}>
          <div>
            <h2 id="sim-checkin" className="text-lg font-semibold">
              Check in a patient
            </h2>
            <p className="text-sm text-muted">What {name} sends after the patient told it who they are.</p>
          </div>
          <form
            className="flex flex-col gap-4"
            onSubmit={async (e) => {
              e.preventDefault();
              const c = await call(() => simCheckIn({ ...checkIn, robot }));
              if (!c) return;
              const r = c.response as {
                doctorKnown?: boolean;
                notified?: boolean;
                spot?: string;
                error?: string;
                visit?: { queuePosition: number | null; estimatedWaitMinutes: number | null };
              } | null;
              if (c.status === 201 && r) {
                const place = r.visit?.queuePosition
                  ? ` ${queueText(r.visit.queuePosition, r.visit.estimatedWaitMinutes)}.`
                  : "";
                setCheckInReply(
                  r.doctorKnown
                    ? `Checked in. Doctor found, map spot "${r.spot}". ${r.notified ? "Teams message sent." : "No Teams message sent."}${place}`
                    : `Checked in, but no doctor matched. It shows up under "Doctor not found" on the board.`,
                );
                setCheckIn((v) => ({ ...v, patientName: "", appointmentTime: "" }));
              } else {
                setCheckInReply(`The server answered ${c.status}${r?.error ? `: ${r.error}` : ""}.`);
              }
            }}
          >
            <Field label="Patient name" htmlFor="sim-name">
              <input
                id="sim-name"
                className={INPUT}
                required
                value={checkIn.patientName}
                onChange={(e) => setCheckIn({ ...checkIn, patientName: e.target.value })}
                autoComplete="off"
              />
            </Field>
            <Field label="Doctor, as the patient said it" htmlFor="sim-doctor">
              <input
                id="sim-doctor"
                className={INPUT}
                list="sim-doctors"
                value={checkIn.doctor}
                onChange={(e) => setCheckIn({ ...checkIn, doctor: e.target.value })}
                autoComplete="off"
              />
              <datalist id="sim-doctors">
                {activeDoctors.map((d) => (
                  <option key={d.id} value={d.name} />
                ))}
              </datalist>
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Appointment" htmlFor="sim-time">
                <input
                  id="sim-time"
                  className={INPUT}
                  type="time"
                  value={checkIn.appointmentTime}
                  onChange={(e) => setCheckIn({ ...checkIn, appointmentTime: e.target.value })}
                />
              </Field>
              <Field label="Language tag" htmlFor="sim-lang">
                <input
                  id="sim-lang"
                  className={INPUT}
                  value={checkIn.language}
                  onChange={(e) => setCheckIn({ ...checkIn, language: e.target.value })}
                  autoComplete="off"
                />
              </Field>
            </div>
            <div>
              <Button kind="primary" type="submit" disabled={busy}>
                Check in as {name}
              </Button>
            </div>
            {checkInReply && <p className="text-sm">{checkInReply}</p>}
          </form>
        </section>

        <section aria-labelledby="sim-inbox" className={PANEL}>
          <div>
            <h2 id="sim-inbox" className="text-lg font-semibold">
              Robot inbox
            </h2>
            <p className="text-sm text-muted">
              {name} asks the server every 3 s if there is something to do: a doctor&apos;s call, something to say or a
              spot to drive to.
            </p>
          </div>
          {command ? (
            <>
              <div className="border-t border-line pt-4">
                <CommandSummary command={command} name={name} />
              </div>
              <ol className="flex flex-col gap-2">
                {steps.map((s, i) => (
                  <li key={s.label} className="flex items-center gap-3">
                    <span
                      className={`flex size-6 shrink-0 items-center justify-center rounded-full text-xs font-semibold ${
                        i < step ? "bg-ok text-on-primary" : i === step ? "bg-primary text-on-primary" : "bg-subtle text-muted"
                      }`}
                    >
                      {i < step ? "✓" : i + 1}
                    </span>
                    <Button
                      kind={i === step ? "primary" : "secondary"}
                      disabled={busy || i !== step}
                      onClick={() => runStep(i)}
                      className="flex-1 justify-start"
                    >
                      {s.label}
                    </Button>
                  </li>
                ))}
              </ol>
              <p className="text-xs text-muted break-words">
                Next request: {steps[Math.min(step, steps.length - 1)].request}
              </p>
            </>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-4">
                <Button kind="primary" disabled={busy} onClick={poll}>
                  Check for commands
                </Button>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    className="size-4 accent-[var(--primary)]"
                    checked={autoPoll}
                    onChange={(e) => setAutoPoll(e.target.checked)}
                  />
                  Keep checking every 3 s
                </label>
              </div>
              <p className="text-sm text-muted">
                {inboxNote ??
                  "Turn off auto checking while the real robot is on, otherwise the simulator takes its commands."}
              </p>
            </>
          )}
        </section>

        <section aria-labelledby="sim-beat" className={PANEL}>
          <div>
            <h2 id="sim-beat" className="text-lg font-semibold">
              Heartbeat
            </h2>
            <p className="text-sm text-muted">What {name} sends every 15 s, so the board shows how it is doing.</p>
          </div>
          <form
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              sendBeat();
            }}
          >
            <div className="flex flex-col gap-1">
              <label htmlFor="sim-battery" className="flex justify-between gap-4 text-sm font-medium">
                <span>Battery</span>
                <span className="tabular-nums">{beat.battery}%</span>
              </label>
              <input
                id="sim-battery"
                type="range"
                min={0}
                max={100}
                step={1}
                className="w-full accent-[var(--primary)]"
                value={beat.battery}
                onChange={(e) => setBeat({ ...beat, battery: Number(e.target.value) })}
              />
            </div>
            <label className="flex items-center gap-3 text-sm font-medium">
              <input
                type="checkbox"
                className="size-4 accent-[var(--primary)]"
                checked={beat.charging}
                onChange={(e) => setBeat({ ...beat, charging: e.target.checked })}
              />
              Charging
            </label>
            <Field label="State" htmlFor="sim-state">
              <select
                id="sim-state"
                className={INPUT}
                value={beat.state}
                onChange={(e) => setBeat({ ...beat, state: e.target.value })}
              >
                {ROBOT_STATES.map((s) => (
                  <option key={s} value={s}>
                    {robotStateLabel(s)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Position" htmlFor="sim-position">
              <input
                id="sim-position"
                className={INPUT}
                list="sim-spots"
                value={beat.position}
                onChange={(e) => setBeat({ ...beat, position: e.target.value })}
                autoComplete="off"
              />
              <datalist id="sim-spots">
                {spots.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </Field>
            <Field label="Goal, what it is doing in plain words" htmlFor="sim-goal">
              <input
                id="sim-goal"
                className={INPUT}
                value={beat.goal}
                onChange={(e) => setBeat({ ...beat, goal: e.target.value })}
                placeholder="Walking Max Weber to Dr. Blue"
                autoComplete="off"
              />
            </Field>
            <div className="flex flex-wrap items-center gap-4">
              <Button kind="primary" type="submit" disabled={busy}>
                Send heartbeat
              </Button>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="size-4 accent-[var(--primary)]"
                  checked={autoBeat}
                  onChange={(e) => setAutoBeat(e.target.checked)}
                />
                Send every 15 s
              </label>
            </div>
            {beatReply && <p className="text-sm">{beatReply}</p>}
          </form>
        </section>

        <section aria-labelledby="sim-help" className={PANEL}>
          <div>
            <h2 id="sim-help" className="text-lg font-semibold">
              Ask a person for help
            </h2>
            <p className="text-sm text-muted">When {name} cannot answer, it calls staff to reception.</p>
          </div>
          <form
            className="flex flex-col gap-4"
            onSubmit={async (e) => {
              e.preventDefault();
              const c = await call(() => simHelp({ ...help, robot }));
              if (!c) return;
              const r = c.response as { notified?: boolean; error?: string } | null;
              setHelpReply(
                c.status === 201
                  ? `Sent. It shows on the board${r?.notified ? " and in Teams" : ""}.`
                  : `The server answered ${c.status}${r?.error ? `: ${r.error}` : ""}.`,
              );
              if (c.status === 201) setHelp({ summary: "", urgency: "normal" });
            }}
          >
            <Field label="What the visitor needs" htmlFor="sim-summary">
              <textarea
                id="sim-summary"
                className={`${INPUT} min-h-24`}
                required
                value={help.summary}
                onChange={(e) => setHelp({ ...help, summary: e.target.value })}
              />
            </Field>
            <fieldset className="flex flex-wrap gap-4">
              <legend className="mb-1 text-sm font-medium">Urgency</legend>
              {["normal", "urgent"].map((u) => (
                <label key={u} className="flex items-center gap-2 text-base">
                  <input
                    type="radio"
                    name="urgency"
                    className="size-4 accent-[var(--primary)]"
                    checked={help.urgency === u}
                    onChange={() => setHelp({ ...help, urgency: u })}
                  />
                  {u === "urgent" ? "Urgent" : "Normal"}
                </label>
              ))}
            </fieldset>
            <div>
              <Button kind="primary" type="submit" disabled={busy}>
                Send help request
              </Button>
            </div>
            {helpReply && <p className="text-sm">{helpReply}</p>}
          </form>
        </section>
      </div>

      <section aria-labelledby="sim-log" className="mt-12">
        <h2 id="sim-log" className="mb-4 text-lg font-semibold">
          Requests sent
        </h2>
        {log.length === 0 ? (
          <p className="text-base text-muted">Nothing sent yet. Each request and the server&apos;s answer shows up here.</p>
        ) : (
          <ol className="border-b border-line">
            {log.map((c, i) => (
              <li key={c.id} className="border-t border-line">
                <details open={i === 0}>
                  <summary className="flex cursor-pointer flex-wrap items-center gap-3 py-4 text-sm">
                    <StatusBadge status={c.status} />
                    <span className="font-mono font-medium break-all">
                      {c.method} {c.path}
                    </span>
                    <span className="text-muted">{clockTime(c.at)}</span>
                  </summary>
                  <div className="grid gap-6 pb-6 lg:grid-cols-2">
                    <Json title="Sent" value={c.body ?? "(no body)"} />
                    <Json title="Got back" value={c.status === 204 ? "(204, no body)" : c.response} />
                  </div>
                </details>
              </li>
            ))}
          </ol>
        )}
      </section>
    </main>
  );
}

function CommandSummary({ command, name }: { command: RobotCommand; name: string }) {
  if (command.type === "say") {
    return (
      <>
        <p className="flex items-center gap-2 text-sm font-medium text-primary-ink">
          <Dot className="bg-primary" pulse /> Staff wants {name} to say something
        </p>
        <p className="text-base font-semibold break-words">&ldquo;{command.text}&rdquo;</p>
        <p className="text-sm text-muted">Typed on the board.</p>
      </>
    );
  }
  if (command.type === "go_to") {
    return (
      <>
        <p className="flex items-center gap-2 text-sm font-medium text-primary-ink">
          <Dot className="bg-primary" pulse /> Staff sends {name} somewhere
        </p>
        <p className="text-base font-semibold break-words">Drive to {command.spot}</p>
        <p className="text-sm text-muted">{name} stays there until the next command.</p>
      </>
    );
  }
  return (
    <>
      <p className="flex items-center gap-2 text-sm font-medium text-primary-ink">
        <Dot className="bg-primary" pulse /> New call from {command.doctorName}
      </p>
      <p className="text-base font-semibold">
        Bring {command.patientName} to {command.doctorName}
      </p>
      <p className="text-sm text-muted">Map spot: {command.spot}</p>
    </>
  );
}

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={htmlFor} className="text-sm font-medium">
        {label}
      </label>
      {children}
    </div>
  );
}

function StatusBadge({ status }: { status: number }) {
  const tone =
    status >= 400 ? "bg-danger-soft text-danger" : status === 204 ? "bg-subtle text-muted" : "bg-ok-soft text-ok";
  return <span className={`rounded-full px-2 py-1 text-xs font-semibold tabular-nums ${tone}`}>{status}</span>;
}

function Json({ title, value }: { title: string; value: unknown }) {
  return (
    <div className="min-w-0">
      <p className="mb-2 text-xs font-medium text-muted">{title}</p>
      <pre className="rounded-xl border border-line bg-surface p-4 font-mono text-xs leading-5 break-all whitespace-pre-wrap">
        {typeof value === "string" ? value : JSON.stringify(value, null, 2)}
      </pre>
    </div>
  );
}
