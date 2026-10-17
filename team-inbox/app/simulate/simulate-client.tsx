"use client";

import {
  ArrowRight,
  CheckCircle,
  Circle,
  CircleNotch,
  Footprints,
  Info,
  Pause,
  PersonSimpleWalk,
  Play,
  Prohibit,
  Robot,
  SmileyMeh,
  ThumbsUp,
} from "@phosphor-icons/react";
import Link from "next/link";
import { useMemo, useState } from "react";
import type { Pace } from "@/lib/types";
import { LoginNeeded, PAGE, SIM_ROBOT, clockTime, placeText, post } from "@/lib/ui";
import { ConversationCard } from "../_reception/live-panel";
import { SimBadge } from "../components/sim-badge";
import { Button, Segmented, Switch } from "../components/button";
import { MapEmpty, MapStats, imageHref, useLiveMap, useStale } from "../components/live-map";
import { MapLegend, MapView, spotsOf } from "../components/map-view";
import { RobotScreen } from "../components/robot-screen";
import { useAction, useBoard } from "../components/use-board";
import { usePoll } from "../components/use-poll";

type Step = { key: string; title: string; state: "todo" | "now" | "done" | "skipped"; note?: string };
type Sim = {
  robot: string;
  on: boolean;
  speed: number;
  busy: string | null;
  walk: { ticket: string; to: string; phase: string; pace: Pace; progress: number } | null;
  canAct: boolean;
  demo: { steps: Step[]; journeyId?: string; ticket?: string; error?: string; running: boolean; finishedAt?: string } | null;
  log: { at: string; text: string }[];
  floor: { mapName: string; real: boolean; missingOnMap: string[] } | null;
  plan: { reception: string | null; room: string | null; waiting: string | null; exit: string | null; missing: string[] };
};

const PANEL = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6";

const ACTIONS: { what: string; label: string; icon: typeof ThumbsUp; help: string }[] = [
  { what: "fine", label: "Walks fine", icon: ThumbsUp, help: "Otto looks back: all good" },
  { what: "behind", label: "Falls behind", icon: Footprints, help: "Otto waits, then walks slower" },
  { what: "blocked", label: "Path blocked", icon: Prohibit, help: "Otto asks to pass, then takes another way" },
  { what: "slower", label: "Says “slower”", icon: SmileyMeh, help: "Otto slows down one step" },
];

export function SimulateClient() {
  const { data: sim, reload: reloadSim } = usePoll<Sim>("/api/sim", { every: (d) => (d?.on ? 1000 : 3000) });
  const { data: board, reload } = useBoard(2000, SIM_ROBOT);
  const live = useLiveMap(SIM_ROBOT);
  const stale = useStale(live.map);
  const { run, busy, error, clearError } = useAction(async () => {
    await Promise.all([reload(), reloadSim()]);
  });
  const [note, setNote] = useState<string | null>(null);

  const act = (key: string, body: Record<string, unknown>) =>
    run(key, async () => {
      setNote(null);
      await post("/api/sim", body);
    });

  const places = useMemo(() => board?.places ?? [], [board]);
  const spots = useMemo(() => (live.map ? spotsOf(live.map, places) : []), [live.map, places]);
  const plan = useMemo(() => (live.image ? { ...live.image, href: imageHref(SIM_ROBOT, live.image) } : null), [live.image]);
  const simJourneys = (board?.journeys ?? []).filter((j) => j.robot === SIM_ROBOT);
  const demo = sim?.demo;
  const running = Boolean(demo?.running);
  const missing = sim?.plan.missing ?? [];

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
        <div className="min-w-0">
          <h1 className="flex flex-wrap items-center gap-3 text-2xl font-semibold tracking-tight sm:text-3xl">
            Virtual Otto <SimBadge />
          </h1>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            A simulated Otto with its own robot id ({SIM_ROBOT}) walks on the live map between your real places. It only takes walks
            made for the simulation, never the real Otto&apos;s. Its messages say &quot;Simulation&quot;, and its walks don&apos;t count in
            today&apos;s numbers.
          </p>
          <p className="mt-2 text-[15px]">
            <Link href="/demo?robot=otto-sim" className="inline-flex items-center gap-1 font-semibold text-primary hover:underline">
              Rehearse the judges&apos; demo script with Virtual Otto <ArrowRight size={16} aria-hidden />
            </Link>
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {running ? (
            <Button size="lg" disabled={busy === "sim:stop"} onClick={() => act("sim:stop", { action: "stop" })}>
              <Pause size={20} aria-hidden /> Stop
            </Button>
          ) : (
            <Button kind="primary" size="lg" disabled={!sim || missing.length > 0 || busy === "sim:demo"} onClick={() => act("sim:demo", { action: "demo" })}>
              <Play size={20} weight="fill" aria-hidden /> {busy === "sim:demo" ? "Starting" : "Play the whole demo"}
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div role="alert" className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger">
          <span className="flex-1">{error}</span>
          <Button kind="ghost" size="sm" onClick={clearError}>
            Dismiss
          </Button>
        </div>
      )}
      {missing.length > 0 && (
        <p className="mb-4 flex flex-wrap items-center gap-2 rounded-xl bg-warn-soft px-4 py-3 text-[15px] text-warn">
          <Info size={20} aria-hidden className="shrink-0" />
          <span className="min-w-0 flex-1">For the demo add {missing.join(" and ")} first. Virtual Otto only walks between your real places.</span>
          <Link href="/settings/places" className="inline-flex h-10 items-center gap-1 rounded-lg px-2 font-semibold text-warn hover:bg-warn-soft">
            Settings, Places <ArrowRight size={16} aria-hidden />
          </Link>
        </p>
      )}

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,7fr)_minmax(0,5fr)]">
        <div className="grid gap-5">
          <section className={PANEL} aria-labelledby="sim-map-title">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h2 id="sim-map-title" className="flex items-center gap-2 text-xl font-semibold tracking-tight">
                <Robot size={24} aria-hidden /> On the map
              </h2>
              <div className="min-w-60">
                <Switch
                  id="sim-on"
                  checked={Boolean(sim?.on)}
                  disabled={!sim || running || busy === "sim:on"}
                  onChange={(v) => act("sim:on", { action: v ? "on" : "off" })}
                  label="Virtual Otto is on"
                  description={sim?.on ? `Takes walks for ${SIM_ROBOT}, runs ${sim.speed}x as fast as the real Otto` : "Off: its open walks were closed"}
                />
              </div>
            </div>
            {sim?.floor && !sim.floor.real && (
              <p className="mt-3 flex items-start gap-2 rounded-xl bg-subtle px-3 py-2.5 text-sm text-muted">
                <Info size={18} aria-hidden className="mt-px shrink-0" />
                No map from the real Otto yet, so Virtual Otto walks on a made-up floor built from your places. Once Otto sends its map, the
                simulation uses the real spots{plan ? "" : " and floor plan"}.
              </p>
            )}
            {sim?.floor?.real && sim.floor.missingOnMap.length > 0 && (
              <p className="mt-3 flex items-start gap-2 rounded-xl bg-subtle px-3 py-2.5 text-sm text-muted">
                <Info size={18} aria-hidden className="mt-px shrink-0" />
                Not on Otto&apos;s map yet, placed next to it: {sim.floor.missingOnMap.join(", ")}.
              </p>
            )}
            {!live.map ? (
              <div className="mt-4">
                <MapEmpty failed={live.failed} loaded={live.loaded} robot={SIM_ROBOT} />
              </div>
            ) : (
              <>
                <div className="mt-4">
                  <MapStats map={live.map} />
                </div>
                <div className="mt-3">
                  <MapView
                    map={live.map}
                    plan={plan}
                    spots={spots}
                    picked={null}
                    onPick={() => undefined}
                    stale={stale}
                    className="aspect-[4/3] sm:aspect-[16/10]"
                    label={`Simulated map of ${live.map.mapName ?? "the floor"}: Virtual Otto${live.map.target ? ` heading to ${live.map.target.name}` : ""}`}
                  />
                </div>
                <div className="mt-3">
                  <MapLegend follower={Boolean(live.map.follower)} target={Boolean(live.map.target)} />
                </div>
              </>
            )}

            <div className="mt-5 border-t border-line pt-5">
              <h3 className="flex items-center gap-2 text-[17px] font-semibold">
                <PersonSimpleWalk size={20} aria-hidden /> The patient
              </h3>
              <p className="mt-0.5 text-sm text-muted">
                {sim?.walk && sim.walk.phase === "walking"
                  ? `${sim.walk.ticket} walks with Virtual Otto to ${sim.walk.to}. Make something happen:`
                  : "These work while Virtual Otto walks with a patient."}
              </p>
              <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-4">
                {ACTIONS.map((a) => {
                  const Icon = a.icon;
                  return (
                    <button
                      key={a.what}
                      type="button"
                      disabled={!sim?.canAct || busy?.startsWith("sim:patient")}
                      onClick={() => act(`sim:patient:${a.what}`, { action: "patient", what: a.what })}
                      className="press flex min-h-20 flex-col items-start justify-between gap-1 rounded-xl border border-line-strong bg-surface p-3 text-left hover:bg-subtle disabled:opacity-45"
                    >
                      <Icon size={22} aria-hidden className="text-primary" />
                      <span className="text-[15px] font-semibold">{a.label}</span>
                      <span className="text-xs text-muted">{a.help}</span>
                    </button>
                  );
                })}
              </div>
            </div>
          </section>

          {board && (
            <section className={PANEL} aria-labelledby="sim-screen-title">
              <h2 id="sim-screen-title" className="text-lg font-semibold">
                Virtual Otto&apos;s screen
              </h2>
              <div className="mt-3 max-w-xl">
                <RobotScreen status={board.robot} />
              </div>
            </section>
          )}
        </div>

        <div className="grid gap-5">
          <section className={PANEL} aria-labelledby="demo-title">
            <h2 id="demo-title" className="text-xl font-semibold tracking-tight">
              The whole demo
            </h2>
            <p className="mt-1 text-sm text-muted">
              {sim?.plan.room
                ? `${sim.plan.reception} → ${sim.plan.waiting ? `${sim.plan.waiting} → ` : ""}${sim.plan.room}${sim.plan.exit ? ` → ${sim.plan.exit}` : ""}. Messages go to ${sim.plan.room}'s real channels, marked as Simulation.`
                : "Check-in, the message to the team, the walk with check-backs, the waiting room, Call in and the exit."}
            </p>
            <ol className="mt-4 grid gap-2">
              {(demo?.steps ?? DEFAULT_STEPS).map((s) => (
                <li key={s.key} className={`flex items-start gap-3 rounded-xl px-3 py-2 ${s.state === "now" ? "bg-primary-soft" : ""}`}>
                  {s.state === "done" ? (
                    <CheckCircle size={22} weight="fill" aria-hidden className="mt-px shrink-0 text-ok" />
                  ) : s.state === "now" ? (
                    <CircleNotch size={22} aria-hidden className="mt-px shrink-0 animate-spin text-primary motion-reduce:animate-none" />
                  ) : (
                    <Circle size={22} aria-hidden className={`mt-px shrink-0 ${s.state === "skipped" ? "text-line" : "text-line-strong"}`} />
                  )}
                  <span className="min-w-0">
                    <span className={`block text-[15px] font-semibold ${s.state === "skipped" ? "text-muted line-through" : s.state === "todo" ? "text-muted" : ""}`}>
                      {s.title}
                    </span>
                    {s.note && <span className="block text-sm text-muted">{s.note}</span>}
                  </span>
                </li>
              ))}
            </ol>
            {demo?.error && demo.error !== "Stopped" && <p className="mt-3 text-[15px] font-medium text-danger">The demo stopped: {demo.error}</p>}
            {demo && !demo.running && !demo.error && demo.finishedAt && (
              <p className="mt-3 text-[15px] font-medium text-ok">Done at {clockTime(demo.finishedAt)}. Play it again any time.</p>
            )}
          </section>

          <SendForm places={places} disabled={running} onSent={async (text) => setNote(text)} ensureOn={async () => {
            if (!sim?.on) await post("/api/sim", { action: "on" });
          }} />
          {note && (
            <p className="-mt-2 rounded-xl bg-subtle px-4 py-3 text-[15px]" role="status">
              {note}
            </p>
          )}

          {simJourneys.length > 0 && (
            <section className={PANEL} aria-labelledby="sim-walks-title">
              <h2 id="sim-walks-title" className="text-lg font-semibold">
                Walks of Virtual Otto
              </h2>
              <ul className="mt-2 grid gap-2">
                {simJourneys.map((j) => {
                  const leg = j.legs[j.legs.length - 1];
                  return (
                    <li key={j.id} className="flex items-center gap-3 text-[15px]">
                      <span className="tabular rounded-lg bg-primary-soft px-2 py-1 font-bold text-primary-ink">{j.ticket}</span>
                      <span className="min-w-0 flex-1 break-words">
                        {placeText({ name: leg.toName, room: leg.toRoom })}, {j.status.replace(/_/g, " ")}
                      </span>
                    </li>
                  );
                })}
              </ul>
              <p className="mt-2 text-sm text-muted">They show on the Reception page too, tagged SIMULATION.</p>
            </section>
          )}

          {board && <ConversationCard feed={board.feed} online={board.robot.online} robot={SIM_ROBOT} run={run} />}

          <section className={PANEL} aria-labelledby="sim-log-title">
            <h2 id="sim-log-title" className="text-lg font-semibold">
              What happened
            </h2>
            {sim?.log.length ? (
              <ol className="mt-2 grid gap-1.5 text-sm">
                {sim.log.map((l, i) => (
                  <li key={`${l.at}-${i}`} className="grid grid-cols-[44px_minmax(0,1fr)] gap-2">
                    <span className="text-muted tabular">{clockTime(l.at)}</span>
                    <span className="break-words">{l.text}</span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="mt-2 text-sm text-muted">Switch Virtual Otto on or play the demo: every step shows up here.</p>
            )}
          </section>
        </div>
      </div>
    </main>
  );
}

const DEFAULT_STEPS: Step[] = [
  { key: "checkin", title: "A patient checks in", state: "todo" },
  { key: "message", title: "The team gets a message", state: "todo" },
  { key: "pickup", title: "Otto picks the ticket up", state: "todo" },
  { key: "walk", title: "The walk, with check-backs", state: "todo" },
  { key: "arrive", title: "The patient sits in the waiting room", state: "todo" },
  { key: "callin", title: "The doctor taps Call in", state: "todo" },
  { key: "room", title: "Otto walks the patient to the room", state: "todo" },
  { key: "staff", title: "With the doctor", state: "todo" },
  { key: "exit", title: "Done: Otto walks the patient to the exit", state: "todo" },
  { key: "end", title: "Goodbye, the walk is closed", state: "todo" },
];

// staff send a patient to virtual otto, like on the reception, but for the simulation
function SendForm({
  places,
  disabled,
  onSent,
  ensureOn,
}: {
  places: { id: string; name: string; room?: string; kind: string; active: boolean; waitingPlaceId?: string }[];
  disabled: boolean;
  onSent: (text: string) => Promise<void>;
  ensureOn: () => Promise<void>;
}) {
  const choices = places.filter((p) => p.active && p.kind !== "reception");
  const [to, setTo] = useState("");
  const [pace, setPace] = useState<Pace>("normal");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const place = choices.find((p) => p.id === to);
  const waiting = place?.waitingPlaceId ? places.find((p) => p.id === place.waitingPlaceId && p.active) : undefined;

  return (
    <section className={PANEL} aria-labelledby="sim-send-title">
      <h2 id="sim-send-title" className="text-xl font-semibold tracking-tight">
        Send a patient yourself
      </h2>
      <p className="mt-1 text-sm text-muted">Virtual Otto picks the ticket up at the reception and walks it there. Use the patient buttons while it walks.</p>
      <label htmlFor="sim-to" className="mt-4 block text-sm font-semibold">
        Where to
      </label>
      <select
        id="sim-to"
        value={to}
        onChange={(e) => setTo(e.target.value)}
        className="mt-2 h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base focus:border-primary focus:outline-none"
      >
        <option value="">{choices.length ? "Pick a place" : "Add places first"}</option>
        {choices.map((p) => (
          <option key={p.id} value={p.id}>
            {placeText(p)}
          </option>
        ))}
      </select>
      <div className="mt-3">
        <Segmented
          label="Pace"
          value={pace}
          onChange={setPace}
          options={[
            { value: "normal", label: "Normal" },
            { value: "slow", label: "Slow" },
            { value: "very_slow", label: "Very slow" },
          ]}
        />
      </div>
      <Button
        kind="primary"
        className="mt-4 w-full"
        disabled={!place || busy || disabled}
        onClick={async () => {
          if (!place) return;
          setBusy(true);
          setError(null);
          try {
            await ensureOn();
            const res = await post<{ journey: { ticket: string } }>("/api/journeys", {
              to: waiting ? waiting.id : place.id,
              ...(waiting ? { forPlaceId: place.id } : {}),
              pace,
              robot: SIM_ROBOT,
            });
            await onSent(`Ticket ${res.journey.ticket} for ${place.name}${waiting ? `, via ${waiting.name}` : ""}. Virtual Otto is on its way to the reception.`);
            setTo("");
          } catch (e) {
            if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "That did not work");
          }
          setBusy(false);
        }}
      >
        {busy ? "Sending" : `Send Virtual Otto${place ? ` to ${waiting ? waiting.name : place.name}` : ""}`}
      </Button>
      {disabled && <p className="mt-2 text-sm text-muted">The demo is running. Stop it first, or wait until it is done.</p>}
      {error && <p className="mt-2 text-[15px] font-medium text-danger">{error}</p>}
    </section>
  );
}
