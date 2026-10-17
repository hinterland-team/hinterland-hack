"use client";

import { ArrowClockwise, ArrowRight, Copy, Cpu, MapTrifold, Presentation, SlidersHorizontal, TerminalWindow, WarningOctagon } from "@phosphor-icons/react";
import Link from "next/link";
import { useState } from "react";
import { WAITING_TEXT, partTitle } from "@/lib/demo-script";
import type { FloorInfo, MapImageMeta, RobotCommand, RobotStatus } from "@/lib/types";
import { LoginNeeded, ROBOT, clockTime, commandState, commandText, post, seenAgo } from "@/lib/ui";
import { Button } from "../../components/button";
import { modeLine, useRobotMode } from "../../components/robot-mode";
import { useNow, usePoll } from "../../components/use-poll";

type Debug = {
  robot: string;
  status: RobotStatus;
  config: { version: number; applied: { version: number; at: string } | null; updatedAt: string | null };
  rawConfig: { version: number; settings: unknown };
  perf: { at: string; text: string; fps: number | null; slowFrames: number | null; dropped: number | null; freezes: number | null; cpu: number | null } | null;
  errors: { count: number; lastAt: string | null };
  map: { name: string | null; updatedAt: string | null; image: MapImageMeta | null; floors: FloorInfo[] };
  commands: RobotCommand[];
  walking: string | null;
};

const CARD = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="text-muted">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </>
  );
}

export function DebugClient() {
  const { data, failed } = usePoll<Debug>(`/api/robot/debug?robot=${ROBOT}`, { every: 5000 });
  const now = useNow(5000);
  const mode = useRobotMode();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const recheck = async () => {
    setBusy(true);
    setNote(null);
    try {
      await post("/api/robot/commands", { robot: ROBOT, type: "recheck" });
      setNote("Sent. Otto checks which server answers and reports it in the heartbeat and the log.");
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setNote(e instanceof Error ? e.message : "Could not send");
    }
    setBusy(false);
  };

  const raw = data ? JSON.stringify(data.rawConfig, null, 2) : "";
  const s = data?.status;
  // otto's answer to the newest recheck: how many servers answer and which one it uses
  const lastRecheck = data?.commands.find((c) => c.type === "recheck");

  return (
    <section aria-labelledby="debug-title">
      <div className="flex flex-wrap items-end justify-between gap-4 pb-4">
        <div>
          <h2 id="debug-title" className="text-xl font-semibold tracking-tight">
            Debug
          </h2>
          <p className="mt-1 max-w-prose text-[15px] text-muted">What Otto runs, where it is and how it is doing. Updates every 5 seconds.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={recheck} disabled={busy}>
            <ArrowClockwise size={18} aria-hidden /> {busy ? "Sending" : "Recheck servers"}
          </Button>
          <Link href="/logs" className="press inline-flex h-11 items-center gap-1 rounded-xl px-3 text-[15px] font-semibold text-primary hover:bg-primary-soft">
            Logs <ArrowRight size={16} aria-hidden />
          </Link>
        </div>
      </div>
      {lastRecheck?.detail ? (
        <p className={`mb-4 max-w-5xl rounded-xl px-4 py-3 text-[15px] ${lastRecheck.ok === false ? "bg-warn-soft text-warn" : "bg-ok-soft"}`} role="status">
          Recheck at {clockTime(lastRecheck.doneAt ?? lastRecheck.createdAt)}: {lastRecheck.detail}
        </p>
      ) : (
        note && (
          <p className="mb-4 max-w-5xl rounded-xl bg-subtle px-4 py-3 text-[15px] text-muted" role="status">
            {note}
          </p>
        )
      )}
      {failed && <p className="mb-4 rounded-xl bg-danger-soft px-4 py-3 text-[15px] text-danger">Can&apos;t reach the server, retrying.</p>}

      {!data || !s ? (
        <div className="grid gap-3 md:grid-cols-2">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-44 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
          ))}
        </div>
      ) : (
        <div className="grid max-w-6xl items-start gap-4 md:grid-cols-2">
          <div className={CARD}>
            <h3 className="flex items-center gap-2 text-[17px] font-semibold">
              <Cpu size={20} aria-hidden className="text-primary" /> Otto&apos;s app
            </h3>
            <dl className="mt-3 grid grid-cols-[120px_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[15px]">
              <Row label="Status">
                {s.online ? <span className="font-medium text-ok">Online</span> : <span className="text-muted">Offline</span>}
                {s.lastSeenAt && now !== null && <span className="text-muted">, seen {seenAgo(s.lastSeenAt, now)}</span>}
              </Row>
              <Row label="Version">{s.version ?? "Not reported yet"}</Row>
              <Row label="Server">{s.server ? s.server.replace(/^https?:\/\//, "") : "Not reported yet"}</Row>
              <Row label="Battery">{s.battery === null || s.battery === undefined ? "Unknown" : `${s.battery}%${s.charging ? ", charging" : ""}`}</Row>
              <Row label="Mode">{modeLine(mode) ?? "Normal mode"}</Row>
              <Row label="Doing">{data.walking ? `Walking with ${data.walking}` : (s.goal ?? "Nothing right now")}</Row>
            </dl>
          </div>

          <div className={CARD}>
            <h3 className="flex items-center gap-2 text-[17px] font-semibold">
              <Presentation size={20} aria-hidden className="text-primary" /> Demo mode
            </h3>
            {s.demo ? (
              <dl className="mt-3 grid grid-cols-[120px_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[15px]">
                <Row label="Running">{s.demo.running ? <span className="font-medium text-ok">Yes</span> : "No"}</Row>
                <Row label="Part">
                  {s.demo.part ? `${partTitle(s.demo.part)}${s.demo.step && s.demo.total ? `, step ${s.demo.step} of ${s.demo.total}` : ""}` : "None right now"}
                </Row>
                <Row label="Now">{s.demo.waitingFor ? WAITING_TEXT[s.demo.waitingFor] : s.demo.running ? "Otto is talking" : "Nothing"}</Row>
                <Row label="Otto said">{s.demo.line ?? "Nothing yet"}</Row>
                <Row label="Patient next">{s.demo.nextPatientLine ?? "No line coming up"}</Row>
                <Row label="Ticket">{s.demo.ticket ?? "None yet"}</Row>
                <Row label="Runs done">{s.demo.runs}</Row>
              </dl>
            ) : (
              <p className="mt-3 text-[15px] text-muted">Otto has not reported demo mode. Start it on the Demo page or at the top.</p>
            )}
            <Link href="/demo" className="mt-3 inline-flex h-10 items-center gap-1 rounded-lg px-2 text-[15px] font-semibold text-primary hover:bg-primary-soft">
              Demo page <ArrowRight size={16} aria-hidden />
            </Link>
          </div>

          <div className={CARD}>
            <h3 className="flex items-center gap-2 text-[17px] font-semibold">
              <MapTrifold size={20} aria-hidden className="text-primary" /> Map and floor
            </h3>
            <dl className="mt-3 grid grid-cols-[120px_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[15px]">
              <Row label="Map">{data.map.name ?? "Not reported yet"}</Row>
              <Row label="Localised">{s.localised === true ? "Yes" : s.localised === false ? <span className="text-warn">No, Otto is not sure where it stands</span> : "Not reported yet"}</Row>
              <Row label="Floors">{data.map.floors.length ? data.map.floors.map((f) => f.name + (f.current ? " (here)" : "")).join(", ") : "Only this one"}</Row>
              <Row label="Floor plan">
                {data.map.image
                  ? `${data.map.image.width} x ${data.map.image.height} px, ${data.map.image.resolution} m per pixel, updated ${clockTime(data.map.image.updatedAt)}`
                  : "Not sent yet"}
              </Row>
              <Row label="Live map">{data.map.updatedAt && now !== null ? `updated ${seenAgo(data.map.updatedAt, now)}` : "No frame yet"}</Row>
            </dl>
          </div>

          <div className={CARD}>
            <h3 className="flex items-center gap-2 text-[17px] font-semibold">
              <TerminalWindow size={20} aria-hidden className="text-primary" /> Speed and errors
            </h3>
            {data.perf ? (
              <dl className="mt-3 grid grid-cols-[120px_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[15px]">
                <Row label="Screen">{data.perf.fps === null ? "-" : `${data.perf.fps} frames per second`}</Row>
                <Row label="Slow frames">{data.perf.slowFrames ?? "-"}{data.perf.dropped !== null ? `, ${data.perf.dropped} dropped` : ""}</Row>
                <Row label="Freezes">{data.perf.freezes ?? "-"}</Row>
                <Row label="CPU">{data.perf.cpu === null ? "-" : `${data.perf.cpu} % of one core`}</Row>
                <Row label="Measured">{clockTime(data.perf.at)}, over 30 s</Row>
              </dl>
            ) : (
              <p className="mt-3 text-[15px] text-muted">No speed numbers yet. Otto logs them every 30 seconds while its app runs.</p>
            )}
            <Link
              href={data.errors.count ? "/logs?level=error" : "/logs"}
              className={`mt-3 inline-flex min-h-10 items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-semibold ${data.errors.count ? "bg-danger-soft text-danger" : "text-muted hover:bg-subtle"}`}
            >
              {data.errors.count ? <WarningOctagon size={18} weight="fill" aria-hidden /> : <TerminalWindow size={18} aria-hidden />}
              {data.errors.count ? `${data.errors.count} errors in the last hour` : "No errors in the last hour"}
            </Link>
          </div>

          <div className={CARD}>
            <h3 className="flex items-center gap-2 text-[17px] font-semibold">
              <SlidersHorizontal size={20} aria-hidden className="text-primary" /> Settings
            </h3>
            <dl className="mt-3 grid grid-cols-[120px_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[15px]">
              <Row label="Saved">{data.config.version ? `Version ${data.config.version}` : "Built-in settings"}</Row>
              <Row label="Otto runs">
                {s.configVersion !== null && s.configVersion !== undefined
                  ? `Version ${s.configVersion}`
                  : data.config.applied
                    ? `Version ${data.config.applied.version} (from the log)`
                    : "Not reported yet"}
                {data.config.applied && `, since ${clockTime(data.config.applied.at)}`}
              </Row>
            </dl>
            <Link href="/settings/robot" className="mt-3 inline-flex h-10 items-center gap-1 rounded-lg px-2 text-sm font-semibold text-primary hover:bg-primary-soft">
              Change the settings <ArrowRight size={16} aria-hidden />
            </Link>
          </div>

          <div className={`${CARD} md:col-span-2`}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <h3 className="text-[17px] font-semibold">Raw config, as Otto gets it</h3>
              <Button
                size="sm"
                onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(raw);
                    setCopied(true);
                    setTimeout(() => setCopied(false), 1500);
                  } catch {
                    setCopied(false);
                  }
                }}
              >
                <Copy size={16} aria-hidden /> {copied ? "Copied" : "Copy"}
              </Button>
            </div>
            <pre className="mt-3 max-h-80 overflow-auto rounded-xl bg-subtle p-3 font-mono text-xs leading-relaxed">{raw}</pre>
            <h3 className="mt-5 text-[15px] font-semibold">Sent to Otto lately</h3>
            {data.commands.length ? (
              <ol className="mt-2 grid gap-1.5 text-sm">
                {data.commands.map((c) => (
                  <li key={c.id} className="grid grid-cols-[44px_minmax(0,1fr)_auto] items-baseline gap-2">
                    <span className="text-muted tabular">{clockTime(c.createdAt)}</span>
                    <span className="break-words">
                      {commandText(c)}
                      {c.detail && <span className={`block text-[13px] ${c.ok === false ? "text-warn" : "text-muted"}`}>{c.detail}</span>}
                    </span>
                    <span className={`text-xs font-semibold ${c.ok === false ? "text-warn" : "text-muted"}`}>
                      {{ queued: "Waiting", delivered: "Otto has it", done: "Done", cancelled: "Cancelled", refused: "Not done" }[commandState(c)]}
                    </span>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="mt-2 text-sm text-muted">Nothing yet.</p>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
