"use client";

import {
  ArrowsClockwise,
  BellRinging,
  ChatCircleText,
  CheckCircle,
  Circle,
  DoorOpen,
  Heartbeat,
  Robot,
  ThumbsDown,
  ThumbsUp,
  Warning,
} from "@phosphor-icons/react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { JourneyWithQueue, Pace, Place, RobotCommand, RobotScreen as Screen } from "@/lib/types";
import { PACE_LABEL, PAGE, WALK_ACTION_DONE, WALK_ACTION_LABEL, clockTime, placeText } from "@/lib/ui";
import { Button, Segmented, Switch } from "../components/button";
import { RobotScreen } from "../components/robot-screen";
import {
  type RobotCall,
  simByTicket,
  simCommandDone,
  simCreate,
  simFeed,
  simHeartbeat,
  simHelp,
  simPatch,
  simPlaces,
  simPoll,
} from "./actions";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";
const PANEL = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6";

type Step = "fetch" | "confirm" | "walk" | "walking" | "arrived" | "failed";
type Feed = { who: "nova" | "patient"; kind: "said" | "intent" | "action"; text: string };

export function SimulateClient() {
  const [robot, setRobot] = useState("nova");
  const [command, setCommand] = useState<RobotCommand | null>(null);
  const [step, setStep] = useState<Step>("fetch");
  const [progress, setProgress] = useState(0);
  const [autoPoll, setAutoPoll] = useState(false);
  const [autoBeat, setAutoBeat] = useState(false);
  const [battery, setBattery] = useState(80);
  const [charging, setCharging] = useState(false);
  const [position, setPosition] = useState("");
  const [screen, setScreen] = useState<Screen>({ view: "idle", title: "Hello, I'm Nova", step: "I can walk you to your room" });
  const [log, setLog] = useState<RobotCall[]>([]);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const state = useRef({ battery, charging, position, screen, robot });
  state.current = { battery, charging, position, screen, robot };

  const record = useCallback((c: RobotCall) => {
    setLog((l) => [c, ...l].slice(0, 30));
    return c;
  }, []);

  const beat = useCallback(
    async (patch: { state?: string; goal?: string | null; position?: string; screen?: Screen } = {}) => {
      const s = state.current;
      if (patch.screen) setScreen(patch.screen);
      if (patch.position !== undefined) setPosition(patch.position);
      return record(
        await simHeartbeat({
          robot: s.robot,
          battery: s.battery,
          charging: s.charging,
          state: patch.state ?? (s.charging ? "charging" : "idle"),
          position: patch.position ?? (s.position || null),
          goal: patch.goal === undefined ? null : patch.goal,
          screen: patch.screen ?? s.screen,
        }),
      );
    },
    [record],
  );

  const feed = useCallback(
    async (items: Feed[], journeyId?: string) => record(await simFeed({ robot: state.current.robot, journeyId, items })),
    [record],
  );

  const poll = useCallback(async () => {
    const c = record(await simPoll(state.current.robot));
    if (c.status === 200 && c.response && typeof c.response === "object") {
      const cmd = c.response as RobotCommand;
      setCommand(cmd);
      setStep("fetch");
      setProgress(0);
      if (cmd.type === "guide") {
        await feed([{ who: "nova", kind: "action", text: `Got the guide for ${cmd.ticket} to ${cmd.to.name}` }], cmd.journeyId);
      }
    }
  }, [record, feed]);

  useEffect(() => {
    if (!autoPoll || command) return;
    const t = setInterval(() => poll().catch(() => undefined), 3000);
    return () => clearInterval(t);
  }, [autoPoll, command, poll]);

  useEffect(() => {
    if (!autoBeat) return;
    beat().catch(() => undefined);
    const t = setInterval(() => beat().catch(() => undefined), 15000);
    return () => clearInterval(t);
  }, [autoBeat, beat]);

  const act = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setNote(null);
    try {
      await fn();
    } catch (e) {
      setNote(e instanceof Error ? e.message : "That did not work");
    }
    setBusy(false);
  };

  const finish = async () => {
    if (!command) return;
    record(await simCommandDone(command.id));
    setCommand(null);
    setStep("fetch");
    setProgress(0);
  };

  const goHome = async (reception: string) => {
    await finish();
    await beat({
      state: "idle",
      goal: null,
      position: reception,
      screen: { view: "idle", title: "Hello, I'm Nova", step: "I can walk you to your room" },
    });
  };

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="pt-6 pb-4">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Simulate Nova</h1>
        <p className="mt-1 max-w-prose text-[15px] text-muted">
          Plays the robot, so the whole loop can be shown without the hardware. It sends the same requests as the Nova Clinic app.
        </p>
      </div>
      <p className="mb-4 flex items-start gap-2 rounded-xl bg-warn-soft px-4 py-3 text-[15px] text-warn">
        <Warning size={20} aria-hidden className="mt-0.5 shrink-0" />
        Keep automatic checking off while the real Nova is running, otherwise the simulator takes its jobs.
      </p>

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)]">
        <div className="grid gap-5">
          <section className={PANEL} aria-labelledby="job-title">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 id="job-title" className="flex items-center gap-2 text-xl font-semibold tracking-tight">
                <Robot size={24} aria-hidden /> Nova&apos;s job
              </h2>
              <Button kind="primary" disabled={busy || Boolean(command)} onClick={() => act(poll)}>
                <ArrowsClockwise size={18} aria-hidden /> Check for jobs
              </Button>
            </div>
            <div className="mt-3 grid gap-x-6 sm:grid-cols-2">
              <Switch
                id="auto-poll"
                checked={autoPoll}
                onChange={setAutoPoll}
                label="Check every 3 s"
                description="Like the robot does"
              />
              <div className="flex min-h-12 items-center gap-3">
                <label htmlFor="robot-name" className="text-[15px] font-semibold">
                  Robot
                </label>
                <input
                  id="robot-name"
                  className={`${INPUT} h-10 max-w-40`}
                  value={robot}
                  onChange={(e) => setRobot(e.target.value)}
                />
              </div>
            </div>

            {!command ? (
              <div className="mt-4 rounded-xl bg-subtle p-5 text-[15px] text-muted">
                No job right now. Send a patient on the Reception page (or check in below as a patient), then check for jobs.
              </div>
            ) : command.type === "guide" ? (
              <GuideJob
                command={command}
                step={step}
                progress={progress}
                screen={screen}
                busy={busy}
                run={(fn) => act(fn)}
                setStep={setStep}
                setProgress={setProgress}
                patch={async (body) => record(await simPatch(command.journeyId, { legId: command.legId, ...body }))}
                beat={beat}
                feed={(items) => feed(items, command.journeyId)}
                help={async (summary) =>
                  record(
                    await simHelp({ robot, summary, urgency: "normal", ticket: command.ticket, journeyId: command.journeyId }),
                  )
                }
                position={position}
                goHome={() => goHome(command.from.name)}
              />
            ) : (
              <div className="mt-4 rounded-xl border border-line p-4">
                <p className="text-sm font-semibold text-muted">
                  {command.type === "say"
                    ? "Say out loud"
                    : command.type === "ask"
                      ? command.origin === "phone"
                        ? "The patient asked on the phone"
                        : "Staff told Nova"
                      : command.type === "walk_control"
                        ? command.origin === "phone"
                          ? "The patient tapped on the phone"
                          : "Staff tapped on the board"
                        : "Drive to"}
                </p>
                <p className="mt-1 text-lg font-semibold break-words">
                  {command.type === "go_to"
                    ? command.spot
                    : command.type === "walk_control"
                      ? WALK_ACTION_LABEL[command.action]
                      : `“${command.text}”`}
                </p>
                <Button
                  kind="primary"
                  className="mt-4"
                  disabled={busy}
                  onClick={() =>
                    act(async () => {
                      if (command.type === "say") await feed([{ who: "nova", kind: "said", text: command.text }]);
                      if (command.type === "ask")
                        await feed([{ who: "nova", kind: "action", text: `Thinking about: ${command.text}` }]);
                      if (command.type === "go_to") await beat({ state: "idle", goal: null, position: command.spot });
                      if (command.type === "walk_control")
                        await feed([{ who: "nova", kind: "action", text: WALK_ACTION_DONE[command.action] }]);
                      await finish();
                    })
                  }
                >
                  <CheckCircle size={18} aria-hidden /> {command.type === "go_to" ? "Arrived there" : "Done"}
                </Button>
              </div>
            )}
            {note && <p className="mt-3 text-[15px] font-medium text-danger">{note}</p>}
          </section>

          <TalkPanel feed={(items) => feed(items, command?.type === "guide" ? command.journeyId : undefined)} />
        </div>

        <div className="grid gap-5">
          <CheckInPanel robot={robot} record={record} />
          <section className={PANEL} aria-labelledby="beat-title">
            <h2 id="beat-title" className="flex items-center gap-2 text-lg font-semibold">
              <Heartbeat size={22} aria-hidden /> Heartbeat
            </h2>
            <label htmlFor="battery" className="mt-3 flex justify-between text-[15px] font-semibold">
              Battery <span className="tabular text-muted">{battery}%</span>
            </label>
            <input
              id="battery"
              type="range"
              min={0}
              max={100}
              value={battery}
              onChange={(e) => setBattery(Number(e.target.value))}
              className="mt-2 w-full accent-[var(--primary)]"
            />
            <div className="mt-2 grid gap-x-6 sm:grid-cols-2">
              <Switch id="charging" checked={charging} onChange={setCharging} label="Charging" />
              <Switch id="auto-beat" checked={autoBeat} onChange={setAutoBeat} label="Every 15 s" />
            </div>
            <label htmlFor="position" className="mt-2 block text-[15px] font-semibold">
              Where Nova is
            </label>
            <input
              id="position"
              className={`${INPUT} mt-2`}
              value={position}
              onChange={(e) => setPosition(e.target.value)}
              placeholder="A map spot"
            />
            <Button className="mt-3" disabled={busy} onClick={() => act(() => beat())}>
              Send heartbeat now
            </Button>
          </section>
          <HelpPanel robot={robot} record={record} />
          <Log log={log} />
        </div>
      </div>
    </main>
  );
}

function GuideJob({
  command,
  step,
  progress,
  screen,
  busy,
  run,
  setStep,
  setProgress,
  patch,
  beat,
  feed,
  help,
  position,
  goHome,
}: {
  command: Extract<RobotCommand, { type: "guide" }>;
  step: Step;
  progress: number;
  screen: Screen;
  busy: boolean;
  run: (fn: () => Promise<unknown>) => void;
  setStep: (s: Step) => void;
  setProgress: (p: number) => void;
  patch: (body: { status?: string; event?: { type: string; detail?: string } }) => Promise<RobotCall>;
  beat: (p: { state?: string; goal?: string | null; position?: string; screen?: Screen }) => Promise<RobotCall>;
  feed: (items: Feed[]) => Promise<RobotCall>;
  help: (summary: string) => Promise<RobotCall>;
  position: string;
  goHome: () => Promise<void>;
}) {
  const t = command.ticket;
  const to = placeText({ name: command.to.name, room: command.to.room });
  const goal = `Guiding ${t} to ${command.to.room ? `Room ${command.to.room}` : command.to.name}`;
  const ok = (c: RobotCall) => {
    if (c.status >= 400) throw new Error(`${c.status}: ${(c.response as { error?: string } | null)?.error ?? "refused"}`);
    return c;
  };
  const walkScreen = (p: number, stepText: string): Screen => ({
    view: "walking",
    ticket: t,
    title: `Walking to ${command.to.name}`,
    step: stepText,
    progress: p,
  });

  const steps: { key: Step; title: string }[] = [
    { key: "fetch", title: `Fetch ${t} at ${command.from.name}` },
    { key: "confirm", title: "Patient confirms the ticket" },
    { key: "walk", title: `Start walking to ${to}` },
    { key: "walking", title: "On the way, with check-backs" },
    { key: "arrived", title: `Arrived at ${to}` },
  ];
  const order = steps.map((s) => s.key);
  const at = step === "failed" ? -1 : order.indexOf(step);

  return (
    <div className="mt-4">
      <div className="flex flex-wrap items-center gap-4">
        <span className="tabular rounded-xl bg-primary-soft px-4 py-2 text-4xl font-bold tracking-tight text-primary-ink">
          {t}
        </span>
        <div className="min-w-0">
          <p className="text-[17px] font-semibold">
            {command.from.name} to {to}
          </p>
          <p className="text-sm text-muted">
            {PACE_LABEL[command.pace as Pace]} pace{command.wheelchair ? ", wheelchair" : ""}. Spot:{" "}
            <span className="font-mono">{command.to.spot}</span>
          </p>
        </div>
      </div>
      <div className="mt-4 max-w-md">
        <RobotScreen status={{ robot: "nova", online: true, screen }} />
      </div>

      <ol className="mt-5 grid gap-3">
        {steps.map((s, i) => {
          const done = at > i;
          const current = at === i;
          return (
            <li key={s.key} className={`rounded-xl border p-3 ${current ? "border-primary bg-primary-soft/40" : "border-line"}`}>
              <p className="flex items-center gap-2 font-semibold">
                {done ? (
                  <CheckCircle size={20} weight="fill" className="text-ok" aria-hidden />
                ) : (
                  <Circle size={20} className={current ? "text-primary" : "text-line-strong"} aria-hidden />
                )}
                <span className={done ? "text-muted" : ""}>{s.title}</span>
              </p>
              {current && (
                <div className="mt-3 flex flex-wrap gap-2">
                  {s.key === "fetch" && (
                    <Button
                      kind="primary"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          ok(await patch({ status: "pickup" }));
                          await beat({
                            state: "fetching",
                            goal: `Picking up ${t} at ${command.from.name}`,
                            position: command.from.name,
                            screen: {
                              view: "pickup",
                              ticket: t,
                              title: "Please come to me",
                              step: `Going to ${command.to.name}`,
                            },
                          });
                          await feed([{ who: "nova", kind: "said", text: `Ticket ${t}, please come to Nova.` }]);
                          setStep("confirm");
                        })
                      }
                    >
                      Drive to pickup
                    </Button>
                  )}
                  {s.key === "confirm" && (
                    <>
                      <Button
                        kind="primary"
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ event: { type: "patient_confirmed", detail: "Patient tapped That's me" } }));
                            await feed([
                              { who: "patient", kind: "intent", text: `confirmed ticket ${t}` },
                              { who: "nova", kind: "said", text: "Hello! Shall I walk normally or a bit slower?" },
                            ]);
                            setStep("walk");
                          })
                        }
                      >
                        Patient tapped That&apos;s me
                      </Button>
                      <Button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ status: "failed", event: { type: "failed", detail: "Nobody came" } }));
                            await feed([{ who: "nova", kind: "action", text: `Nobody came for ${t}, going back` }]);
                            await goHome();
                          })
                        }
                      >
                        Nobody came
                      </Button>
                    </>
                  )}
                  {s.key === "walk" && (
                    <Button
                      kind="primary"
                      disabled={busy}
                      onClick={() =>
                        run(async () => {
                          ok(await patch({ status: "walking" }));
                          setProgress(0.1);
                          await beat({ state: "walking", goal, screen: walkScreen(0.1, "Follow me, nice and easy") });
                          await feed([{ who: "nova", kind: "said", text: `Let's go. Please follow me to ${to}.` }]);
                          setStep("walking");
                        })
                      }
                    >
                      Start walking
                    </Button>
                  )}
                  {s.key === "walking" && (
                    <>
                      <Button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            const p = Math.min(0.9, progress + 0.25);
                            setProgress(p);
                            ok(await patch({ event: { type: "check_back", detail: "Patient close, all fine" } }));
                            await beat({
                              state: "walking",
                              goal,
                              screen: {
                                view: "check_back",
                                ticket: t,
                                title: "All good?",
                                step: "We're almost there",
                                progress: p,
                              },
                            });
                            await feed([
                              { who: "nova", kind: "said", text: "All good? We're almost there." },
                              { who: "patient", kind: "intent", text: "said all is fine" },
                            ]);
                            await beat({ state: "walking", goal, screen: walkScreen(p, p > 0.6 ? "Almost there" : "Half way") });
                          })
                        }
                      >
                        Check-back: all fine
                      </Button>
                      <Button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ event: { type: "waiting_for_patient", detail: "Patient 4 m behind, Nova waits" } }));
                            ok(await patch({ event: { type: "slowed_down", detail: "Slowed down one step" } }));
                            await beat({
                              state: "walking",
                              goal,
                              screen: {
                                view: "waiting",
                                ticket: t,
                                title: "I'll wait for you",
                                step: "Take your time",
                                progress,
                              },
                            });
                            await feed([
                              { who: "nova", kind: "said", text: "I'll wait for you. Take your time." },
                              { who: "nova", kind: "action", text: "Slowed down one step" },
                            ]);
                          })
                        }
                      >
                        Patient fell behind
                      </Button>
                      <Button
                        kind="quiet"
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            const near = position || command.from.name;
                            ok(await patch({ event: { type: "lost_patient", detail: `Nobody behind Nova near ${near}` } }));
                            await help(`Lost the patient with ticket ${t} near ${near}`);
                            await beat({
                              state: "error",
                              goal: `Looking for ${t}`,
                              screen: {
                                view: "help",
                                ticket: t,
                                title: "I lost my companion",
                                step: "A person from the team is coming",
                              },
                            });
                            await feed([{ who: "nova", kind: "said", text: `Ticket ${t}, where are you? I'll wait here.` }]);
                            ok(await patch({ status: "failed", event: { type: "failed", detail: "Lost the patient" } }));
                            setStep("failed");
                          })
                        }
                      >
                        Lost the patient
                      </Button>
                      <Button
                        kind="primary"
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ status: "arrived" }));
                            setProgress(1);
                            await beat({
                              state: "talking",
                              goal: `Arrived with ${t}`,
                              position: command.to.spot,
                              screen: {
                                view: "arrived",
                                ticket: t,
                                title: `This is ${to}`,
                                step: "Please take a seat, the team knows you're here",
                                progress: 1,
                              },
                            });
                            await feed([
                              {
                                who: "nova",
                                kind: "said",
                                text: `This is ${to}. Please take a seat, the team knows you're here.`,
                              },
                            ]);
                            setStep("arrived");
                          })
                        }
                      >
                        Arrive
                      </Button>
                    </>
                  )}
                  {s.key === "arrived" && (
                    <>
                      <Button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ event: { type: "feedback", detail: "up" } }));
                            await feed([{ who: "patient", kind: "intent", text: "liked the walk" }]);
                          })
                        }
                        aria-label="Feedback thumbs up"
                      >
                        <ThumbsUp size={18} aria-hidden /> Good walk
                      </Button>
                      <Button
                        disabled={busy}
                        onClick={() =>
                          run(async () => {
                            ok(await patch({ event: { type: "feedback", detail: "down" } }));
                            await feed([{ who: "patient", kind: "intent", text: "did not like the walk" }]);
                          })
                        }
                        aria-label="Feedback thumbs down"
                      >
                        <ThumbsDown size={18} aria-hidden /> Not good
                      </Button>
                      <Button kind="primary" disabled={busy} onClick={() => run(goHome)}>
                        Back at reception
                      </Button>
                    </>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ol>
      {step === "failed" && (
        <div className="mt-3 rounded-xl border border-danger bg-danger-soft p-3">
          <p className="font-semibold text-danger">The leg failed. Staff can send Nova again on the Reception page.</p>
          <Button className="mt-3" disabled={busy} onClick={() => run(goHome)}>
            Back at reception
          </Button>
        </div>
      )}
    </div>
  );
}

function TalkPanel({ feed }: { feed: (items: Feed[]) => Promise<RobotCall> }) {
  const [nova, setNova] = useState("");
  const [patient, setPatient] = useState("");
  const [result, setResult] = useState<string | null>(null);
  return (
    <section className={PANEL} aria-labelledby="talk-title">
      <h2 id="talk-title" className="flex items-center gap-2 text-lg font-semibold">
        <ChatCircleText size={22} aria-hidden /> Conversation
      </h2>
      <form
        className="mt-3"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!nova.trim()) return;
          await feed([{ who: "nova", kind: "said", text: nova }]);
          setNova("");
        }}
      >
        <label htmlFor="nova-says" className="text-[15px] font-semibold">
          Nova says
        </label>
        <div className="mt-2 flex gap-2">
          <input id="nova-says" className={INPUT} value={nova} onChange={(e) => setNova(e.target.value)} />
          <Button kind="secondary" className="h-12" type="submit" disabled={!nova.trim()}>
            Send
          </Button>
        </div>
      </form>
      <form
        className="mt-4"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!patient.trim()) return;
          const c = await feed([{ who: "patient", kind: "said", text: patient }]);
          const r = c.response as { dropped?: number; showPatientText?: boolean } | null;
          setResult(
            r?.dropped
              ? "Not kept: the demo switch on the Reception page is off, so only intents are stored."
              : "Shown on the Reception page.",
          );
          setPatient("");
        }}
      >
        <label htmlFor="patient-says" className="text-[15px] font-semibold">
          Patient says (only kept with the demo switch on)
        </label>
        <div className="mt-2 flex gap-2">
          <input id="patient-says" className={INPUT} value={patient} onChange={(e) => setPatient(e.target.value)} />
          <Button kind="secondary" className="h-12" type="submit" disabled={!patient.trim()}>
            Send
          </Button>
        </div>
        {result && <p className="mt-2 text-sm text-muted">{result}</p>}
      </form>
    </section>
  );
}

function CheckInPanel({ robot, record }: { robot: string; record: (c: RobotCall) => RobotCall }) {
  const [places, setPlaces] = useState<Place[] | null>(null);
  const [room, setRoom] = useState<string | null>(null);
  const [walk, setWalk] = useState<"normal" | "slow" | "wheelchair">("normal");
  const [publicTo, setPublicTo] = useState("");
  const [ticket, setTicket] = useState("");
  const [answer, setAnswer] = useState<string | null>(null);

  const load = useCallback(async () => {
    const c = record(await simPlaces());
    if (c.status === 200 && Array.isArray(c.response)) setPlaces((c.response as Place[]).filter((p) => p.active));
  }, [record]);

  useEffect(() => {
    load().catch(() => undefined);
  }, [load]);

  const rooms = places?.filter((p) => p.selfCheckIn) ?? [];
  const pace: Pace = walk === "normal" ? "normal" : "slow";

  const create = async (to: string, forPlaceId?: string) => {
    const c = record(await simCreate({ to, forPlaceId, pace, wheelchair: walk === "wheelchair", robot }));
    const r = c.response as { journey?: JourneyWithQueue; error?: string } | null;
    setAnswer(
      c.status === 201 && r?.journey
        ? `Ticket ${r.journey.ticket}. Check for jobs to walk it.`
        : `${c.status}: ${r?.error ?? "refused"}`,
    );
  };

  return (
    <section className={PANEL} aria-labelledby="checkin-title">
      <div className="flex items-center justify-between gap-2">
        <h2 id="checkin-title" className="flex items-center gap-2 text-lg font-semibold">
          <DoorOpen size={22} aria-hidden /> A patient at the robot
        </h2>
        <Button kind="ghost" size="sm" onClick={() => load()}>
          Reload places
        </Button>
      </div>
      <p className="mt-1 text-sm text-muted">What a patient picks on Nova&apos;s screen. Still no name, just a ticket.</p>

      <p className="mt-4 text-[15px] font-semibold">Who are you here to see?</p>
      {rooms.length === 0 ? (
        <p className="mt-2 rounded-xl bg-subtle p-3 text-sm text-muted">
          No room is open for self check-in. Switch it on for a room on the Places page.
        </p>
      ) : (
        <div className="mt-2 grid grid-cols-2 gap-2">
          {rooms.map((p) => (
            <button
              key={p.id}
              type="button"
              aria-pressed={room === p.id}
              onClick={() => setRoom(p.id)}
              className={`press min-h-16 rounded-xl border p-3 text-left ${room === p.id ? "border-primary bg-primary-soft ring-2 ring-primary" : "border-line hover:bg-subtle"}`}
            >
              <span className="block font-semibold">{p.name}</span>
              {p.room && <span className="block text-sm text-muted">Room {p.room}</span>}
            </button>
          ))}
        </div>
      )}
      <div className="mt-3">
        <Segmented
          label="How shall I walk?"
          value={walk}
          onChange={setWalk}
          options={[
            { value: "normal", label: "Normal" },
            { value: "slow", label: "Slower" },
            { value: "wheelchair", label: "Wheelchair" },
          ]}
        />
      </div>
      <Button
        kind="primary"
        className="mt-3 w-full"
        disabled={!room}
        onClick={() => {
          const r = rooms.find((p) => p.id === room);
          if (r) create(r.waitingPlaceId ?? r.id, r.id);
        }}
      >
        Confirm check-in
      </Button>

      <label htmlFor="public-to" className="mt-5 block text-[15px] font-semibold">
        Or ask for a place
      </label>
      <div className="mt-2 flex gap-2">
        <select id="public-to" className={INPUT} value={publicTo} onChange={(e) => setPublicTo(e.target.value)}>
          <option value="">Pick a place</option>
          {(places ?? [])
            .filter((p) => p.kind !== "reception")
            .map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.public ? "" : " (staff only, Nova refuses)"}
              </option>
            ))}
        </select>
        <Button className="h-12" disabled={!publicTo} onClick={() => create(publicTo)}>
          Walk me
        </Button>
      </div>

      <form
        className="mt-5"
        onSubmit={async (e) => {
          e.preventDefault();
          const c = record(await simByTicket(ticket));
          const j = c.response as (JourneyWithQueue & { error?: string }) | null;
          setAnswer(
            c.status === 200 && j
              ? j.queuePosition
                ? `${j.ticket}: ${j.queuePosition === 1 ? "next in line" : `number ${j.queuePosition} in line`}${j.estimatedWaitMinutes ? `, about ${j.estimatedWaitMinutes} min` : ""}.`
                : `${j.ticket}: ${j.status.replace(/_/g, " ")}.`
              : `${c.status}: ${j?.error ?? "not found"}`,
          );
        }}
      >
        <label htmlFor="ticket-ask" className="text-[15px] font-semibold">
          How long do I wait? (ticket)
        </label>
        <div className="mt-2 flex gap-2">
          <input
            id="ticket-ask"
            className={`${INPUT} uppercase`}
            value={ticket}
            onChange={(e) => setTicket(e.target.value)}
            maxLength={4}
            placeholder="A17"
          />
          <Button className="h-12" type="submit" disabled={!ticket.trim()}>
            Ask
          </Button>
        </div>
      </form>
      {answer && (
        <p role="status" className="mt-3 rounded-xl bg-subtle px-4 py-3 text-[15px]">
          {answer}
        </p>
      )}
    </section>
  );
}

function HelpPanel({ robot, record }: { robot: string; record: (c: RobotCall) => RobotCall }) {
  const [summary, setSummary] = useState("");
  const [urgent, setUrgent] = useState(false);
  return (
    <section className={PANEL} aria-labelledby="help-title">
      <h2 id="help-title" className="flex items-center gap-2 text-lg font-semibold">
        <BellRinging size={22} aria-hidden /> Call staff
      </h2>
      <form
        className="mt-3"
        onSubmit={async (e) => {
          e.preventDefault();
          if (!summary.trim()) return;
          record(await simHelp({ robot, summary, urgency: urgent ? "urgent" : "normal" }));
          setSummary("");
        }}
      >
        <label htmlFor="help-summary" className="text-[15px] font-semibold">
          What Nova reports (no health details)
        </label>
        <input
          id="help-summary"
          className={`${INPUT} mt-2`}
          value={summary}
          onChange={(e) => setSummary(e.target.value)}
          placeholder="A visitor at the entrance needs a person"
        />
        <Switch id="help-urgent" checked={urgent} onChange={setUrgent} label="Urgent" />
        <Button type="submit" disabled={!summary.trim()}>
          Ask for help
        </Button>
      </form>
    </section>
  );
}

function Log({ log }: { log: RobotCall[] }) {
  return (
    <section className={PANEL} aria-labelledby="log-title">
      <h2 id="log-title" className="text-lg font-semibold">
        Requests
      </h2>
      {log.length === 0 ? (
        <p className="mt-2 text-sm text-muted">Every request the simulator sends shows up here with the answer.</p>
      ) : (
        <ol className="mt-2 grid gap-1">
          {log.map((c) => (
            <li key={c.id}>
              <details className="rounded-lg px-2 py-1 hover:bg-subtle">
                <summary className="grid cursor-pointer grid-cols-[44px_44px_minmax(0,1fr)] items-baseline gap-2 text-sm">
                  <span className="text-muted tabular">{clockTime(c.at)}</span>
                  <span className={`font-mono font-semibold ${c.status >= 400 ? "text-danger" : "text-ok"}`}>{c.status}</span>
                  <span className="truncate font-mono">
                    {c.method} {c.path}
                  </span>
                </summary>
                <pre className="mt-1 max-h-48 overflow-auto rounded-lg bg-subtle p-2 text-xs whitespace-pre-wrap break-words">
                  {JSON.stringify({ sent: c.body ?? null, answer: c.response }, null, 2)}
                </pre>
              </details>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
