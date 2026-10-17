"use client";

import { ArrowClockwise, ArrowRight, Pause, Play, SkipForward, Stop } from "@phosphor-icons/react";
import Link from "next/link";
import { memo, useEffect, useRef, useState } from "react";
import {
  DEMO_PARTS,
  type DemoOp,
  type DemoScript,
  type DemoState,
  PART_INFO,
  QUICK_LINES,
  WAITING_TEXT,
  partTitle,
  stepText,
} from "@/lib/demo-script";
import type { DemoView } from "@/lib/demo-view";
import { MODE_INFO } from "@/lib/modes";
import { SIM_ROBOT } from "@/lib/robots";
import { LoginNeeded, PAGE, ROBOT, clockTime, commandState, commandText, post, seenAgo } from "@/lib/ui";
import { Button, Segmented, Switch } from "../components/button";
import { SimBadge } from "../components/sim-badge";
import { useNow, usePoll } from "../components/use-poll";

const PANEL = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6";
const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";
const ROBOT_KEY = "otto-demo-robot";
const SPEAK_KEY = "otto-demo-speak";

function readLocal(key: string) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeLocal(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // private window: only for this visit
  }
}

// "Speak Otto's lines on this device": reads each new line aloud (for rehearsing with virtual otto). off by default
function useSpeech(demo: DemoState | null, robot: string) {
  const [on, setOn] = useState(false);
  const [supported, setSupported] = useState(true);
  const last = useRef<string | null>(null);

  useEffect(() => {
    const ok = typeof window !== "undefined" && "speechSynthesis" in window;
    setSupported(ok);
    if (ok && readLocal(SPEAK_KEY) === "on") setOn(true);
  }, []);

  // a new line (or the same words in another part or run) is spoken once; turning it on starts with the next line
  const key = demo?.running && demo.line ? `${robot}|${demo.runs}|${demo.part ?? ""}|${demo.line}` : null;
  useEffect(() => {
    if (!on || !key || !demo?.line) {
      last.current = key;
      return;
    }
    if (last.current === key) return;
    const first = last.current === null;
    last.current = key;
    if (first) return;
    const u = new SpeechSynthesisUtterance(demo.line);
    u.lang = "en-US";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(u);
  }, [on, key, demo?.line]);

  const set = (value: boolean) => {
    setOn(value);
    writeLocal(SPEAK_KEY, value ? "on" : "off");
    if (!supported) return;
    if (value) {
      // a tap starts speech on phones: say a short word now, then the next lines follow
      last.current = key;
      const u = new SpeechSynthesisUtterance("Okay");
      u.lang = "en-US";
      window.speechSynthesis.speak(u);
    } else window.speechSynthesis.cancel();
  };
  return { on, set, supported };
}

export function DemoClient() {
  const [robot, setRobot] = useState<string>(ROBOT);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const fromUrl = new URLSearchParams(window.location.search).get("robot");
    const saved = readLocal(ROBOT_KEY);
    const pick = fromUrl === SIM_ROBOT || fromUrl === ROBOT ? fromUrl : saved === SIM_ROBOT ? SIM_ROBOT : ROBOT;
    setRobot(pick);
    setReady(true);
  }, []);
  const { data, failed, reload } = usePoll<DemoView>(ready ? `/api/demo?robot=${robot}` : null, { every: 1000 });
  const now = useNow(5000);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [text, setText] = useState("");
  const demo = data?.robot === robot ? data.demo : null;
  const speech = useSpeech(demo, robot);
  const sim = robot === SIM_ROBOT;
  const name = sim ? "Virtual Otto" : "Otto";
  const running = demo?.running === true;
  const holding = demo?.waitingFor === "hold";

  const send = async (op: DemoOp, line?: string) => {
    setBusy(op);
    setError(null);
    try {
      await post("/api/demo", { robot, op, ...(line ? { text: line } : {}) });
      if (op === "say" && line === text) setText("");
      reload();
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not send");
    }
    setBusy(null);
  };

  const pickRobot = (value: string) => {
    setRobot(value);
    writeLocal(ROBOT_KEY, value);
  };

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
        <div className="min-w-0">
          <h1 className="flex flex-wrap items-center gap-3 text-2xl font-semibold tracking-tight sm:text-3xl">
            Demo {sim && <SimBadge />}
          </h1>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            The rehearsed demo for the judges. Otto only says the script&apos;s lines, the microphone stays off.{" "}
            <Link href="/settings/demo" className="font-semibold text-primary hover:underline">
              Edit the script
            </Link>
          </p>
        </div>
        <div className="w-full sm:w-72">
          <Segmented
            label="Which Otto"
            value={robot}
            onChange={pickRobot}
            options={[
              { value: ROBOT, label: "Otto" },
              { value: SIM_ROBOT, label: "Virtual Otto" },
            ]}
          />
        </div>
      </div>

      <StatusLine view={data?.robot === robot ? data : null} name={name} now={now} failed={failed} />

      {error && (
        <p role="alert" className="mt-4 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] font-medium text-danger">
          {error}
        </p>
      )}

      <div className="mt-4 grid items-start gap-5 lg:grid-cols-[minmax(0,6fr)_minmax(0,5fr)]">
        <div className="grid gap-5">
          <Teleprompter demo={demo} name={name} />

          <section aria-label="Demo controls" className="grid gap-2">
            <Button kind={running ? "primary" : "secondary"} size="xl" className="w-full" disabled={busy !== null || !running} onClick={() => send("next")}>
              <SkipForward size={24} weight="fill" aria-hidden /> Next
            </Button>
            <div className="grid grid-cols-2 gap-2">
              <Button size="lg" disabled={busy !== null || !running} onClick={() => send(holding ? "resume" : "hold")}>
                {holding ? <Play size={20} weight="fill" aria-hidden /> : <Pause size={20} weight="fill" aria-hidden />}
                {holding ? "Resume" : "Hold"}
              </Button>
              <Button size="lg" disabled={busy !== null || !running} onClick={() => send("restart")}>
                <ArrowClockwise size={20} aria-hidden /> Restart
              </Button>
              <Button kind={running ? "secondary" : "primary"} size="lg" disabled={busy !== null} onClick={() => send("start")}>
                <Play size={20} weight="fill" aria-hidden /> {busy === "start" ? "Starting" : "Start"}
              </Button>
              <Button size="lg" disabled={busy !== null} onClick={() => send("stop")}>
                <Stop size={20} weight="fill" aria-hidden /> Stop
              </Button>
            </div>
            <p className="text-sm text-muted">
              Next ends a wait at once (a person, the patient&apos;s line, until the patient is close). On the walk it plays the next walk part:
              Patient fell back comes {data?.script.fellBackAfterS ?? 6} s after the first check-back (or on Next). Hold pauses before the next
              step. {running ? "Start again begins a new run with the saved script." : "Start sends the saved script."}
            </p>
          </section>

          <section className={PANEL} aria-labelledby="say-title">
            <h2 id="say-title" className="text-lg font-semibold">
              Say something
            </h2>
            <p className="mt-0.5 text-sm text-muted">{name} says it right away, word for word. The microphone stays off.</p>
            <form
              className="mt-3 flex gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (text.trim()) send("say", text.trim());
              }}
            >
              <label htmlFor="demo-say" className="sr-only">
                What {name} says
              </label>
              <input
                id="demo-say"
                className={INPUT}
                value={text}
                maxLength={300}
                onChange={(e) => setText(e.target.value)}
                placeholder="Type a line"
                autoComplete="off"
              />
              <Button kind="primary" size="md" className="h-12" type="submit" disabled={!text.trim() || busy !== null}>
                Say
              </Button>
            </form>
            <div className="mt-3 flex flex-wrap gap-2">
              {QUICK_LINES.map((line) => (
                <Button key={line} size="md" disabled={busy !== null} onClick={() => send("say", line)}>
                  {line}
                </Button>
              ))}
            </div>
            <div className="mt-3 border-t border-line pt-2">
              <Switch
                id="demo-speak"
                checked={speech.on}
                onChange={speech.set}
                disabled={!speech.supported}
                label="Speak Otto's lines on this device"
                description={
                  speech.supported
                    ? "For rehearsing with Virtual Otto: this phone or laptop reads each new line aloud."
                    : "This browser can't speak lines."
                }
              />
            </div>
          </section>

          <Answers commands={data?.robot === robot ? data.commands : []} name={name} />
        </div>

        <ScriptCard script={data?.script ?? null} isDefault={data?.isDefault ?? true} demo={demo} />
      </div>
    </main>
  );
}

function StatusLine({ view, name, now, failed }: { view: DemoView | null; name: string; now: number | null; failed: boolean }) {
  if (failed) return <p className="rounded-xl bg-danger-soft px-4 py-3 text-[15px] text-danger">Can&apos;t reach the server, retrying.</p>;
  if (!view) return <div className="h-12 animate-pulse rounded-xl bg-subtle motion-reduce:animate-none" />;
  const demo = view.demo;
  const parts: string[] = [];
  if (view.sim && !view.sim.on) parts.push("Virtual Otto is off. Start switches it on");
  else if (!view.online) parts.push(`${name} is offline${view.seenAt && now !== null ? `, seen ${seenAgo(view.seenAt, now)}` : ""}. It gets the commands when it is back`);
  else parts.push(`${name} is online`);
  if (demo?.running) parts.push(`Demo mode is on${demo.runs ? `, ${demo.runs} ${demo.runs === 1 ? "run" : "runs"} done` : ""}`);
  else if (view.mode && view.mode.mode !== "demo") parts.push(`${MODE_INFO[view.mode.mode].label} mode. Start switches to Demo mode`);
  else if (view.mode?.switching) parts.push("Switching to Demo mode, waiting for Otto");
  else parts.push("Demo mode is off");
  const live = view.online && (!view.sim || view.sim.on);
  return (
    <p className={`flex flex-wrap items-center gap-x-2 rounded-xl px-4 py-3 text-[15px] ${live ? "bg-subtle" : "bg-warn-soft text-warn"}`} role="status">
      <span aria-hidden className={`size-2.5 shrink-0 rounded-full ${live ? (demo?.running ? "live-dot bg-ok" : "bg-ok") : "bg-warn"}`} />
      {parts.join(". ")}.
    </p>
  );
}

const Teleprompter = memo(function Teleprompter({ demo, name }: { demo: DemoState | null; name: string }) {
  const nowWaiting = demo?.running && demo.waitingFor === "patient";
  const line = demo?.running ? demo.nextPatientLine : null;
  const where = demo?.running
    ? [
        demo.part ? `${partTitle(demo.part)}${demo.step && demo.total ? `, step ${demo.step} of ${demo.total}` : ""}` : null,
        demo.waitingFor ? WAITING_TEXT[demo.waitingFor] : demo.part ? `${name} is talking` : null,
        demo.ticket ? `Ticket ${demo.ticket}` : null,
      ].filter(Boolean)
    : [];
  return (
    <section
      aria-labelledby="prompt-title"
      className={`rounded-2xl border p-5 shadow-panel sm:p-6 ${nowWaiting ? "border-primary bg-primary-soft" : "border-line bg-surface"}`}
    >
      <h2 id="prompt-title" className={`text-[15px] font-semibold ${nowWaiting ? "text-primary-ink" : "text-muted"}`}>
        {nowWaiting ? "Patient says now" : "Patient says next"}
      </h2>
      <p className="mt-2 text-[28px] leading-tight font-semibold tracking-tight text-balance sm:text-4xl" aria-live="polite">
        {line ? `“${line}”` : <span className="text-muted">{demo?.running ? "No patient line coming up" : "Start the demo to see the lines"}</span>}
      </p>
      {where.length > 0 && <p className="mt-3 text-[15px] text-muted">{where.join(". ")}.</p>}
      {demo?.running && demo.line && (
        <p className="mt-3 border-t border-line pt-3 text-[17px]">
          <span className="font-semibold">{name}:</span> “{demo.line}”
        </p>
      )}
    </section>
  );
});

const WHO: Record<string, string> = { otto: "Otto", patient: "Patient", action: "Action" };

const ScriptCard = memo(function ScriptCard({ script, isDefault, demo }: { script: DemoScript | null; isDefault: boolean; demo: DemoState | null }) {
  return (
    <section className={PANEL} aria-labelledby="script-title">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="script-title" className="text-lg font-semibold">
          The script
        </h2>
        <Link href="/settings/demo" className="inline-flex items-center gap-1 text-[15px] font-semibold text-primary hover:underline">
          Edit <ArrowRight size={16} aria-hidden />
        </Link>
      </div>
      {script && (
        <p className="mt-0.5 text-sm text-muted">
          {isDefault ? "The default script" : "Your script"}: to {script.destination}, waits at {script.reception},{" "}
          {script.start === "person" ? `starts when a person stands within ${script.personM} m` : "starts with Next"}. The patient walks on
          Otto&apos;s {script.walkSide}
          {script.glanceDeg ? `, Otto turns ${script.glanceDeg}° to look at the first check-back` : ", no turn at the first check-back"}.
        </p>
      )}
      {!script ? (
        <div className="mt-4 h-64 animate-pulse rounded-xl bg-subtle motion-reduce:animate-none" />
      ) : (
        <div className="mt-4 grid gap-5">
          {DEMO_PARTS.filter((p) => (script.parts[p]?.length ?? 0) > 0).map((p) => {
            const here = demo?.running && demo.part === p;
            return (
              <div key={p}>
                <h3 className="flex items-center gap-2 text-[15px] font-semibold">
                  {PART_INFO[p].title}
                  {here && <span className="rounded-full bg-primary px-2 py-0.5 text-xs font-semibold text-on-primary">Now</span>}
                </h3>
                <ol className="mt-2 grid gap-1">
                  {(script.parts[p] ?? []).map((st, i) => {
                    const now = here && demo?.step === i + 1;
                    return (
                      <li
                        key={i}
                        aria-current={now ? "step" : undefined}
                        className={`grid grid-cols-[28px_76px_minmax(0,1fr)] items-baseline gap-2 rounded-lg px-2 py-1.5 text-[15px] ${
                          now ? "bg-primary-soft font-semibold text-primary-ink ring-1 ring-primary" : ""
                        }`}
                      >
                        <span className="text-sm text-muted tabular">{i + 1}</span>
                        <span className={`text-sm font-semibold ${st.who === "patient" ? "text-st-staff" : st.who === "action" ? "text-muted" : "text-primary"}`}>
                          {WHO[st.who]}
                        </span>
                        <span className={`break-words ${st.who === "action" ? "text-muted" : ""}`}>{stepText(st, script)}</span>
                      </li>
                    );
                  })}
                </ol>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
});

function Answers({ commands, name }: { commands: DemoView["commands"]; name: string }) {
  const label = { queued: "Waiting", delivered: `${name} has it`, done: "Done", cancelled: "Cancelled", refused: "Not done" };
  const tone = {
    queued: "bg-subtle text-muted",
    delivered: "bg-primary-soft text-primary-ink",
    done: "bg-ok-soft text-ok",
    cancelled: "bg-subtle text-muted",
    refused: "bg-warn-soft text-warn",
  };
  return (
    <section className={PANEL} aria-labelledby="answers-title">
      <h2 id="answers-title" className="text-lg font-semibold">
        {name}&apos;s answers
      </h2>
      {commands.length ? (
        <ol className="mt-3 grid gap-2">
          {commands.map((c) => {
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
      ) : (
        <p className="mt-2 text-sm text-muted">Nothing sent yet. {name} answers every command here.</p>
      )}
    </section>
  );
}
