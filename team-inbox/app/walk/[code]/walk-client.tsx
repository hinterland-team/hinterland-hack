"use client";

import {
  CheckCircle,
  HandPalm,
  LinkBreak,
  Microphone,
  PaperPlaneRight,
  PersonSimpleRun,
  PersonSimpleWalk,
  Play,
  ShieldCheck,
  Wheelchair,
} from "@phosphor-icons/react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { WalkAction } from "@/lib/types";
import type { WalkPhase, WalkView } from "@/lib/walk";
import { PACE_LABEL } from "@/lib/ui";
import { Mascot } from "../../components/mascot";

const POLL_MS = 3000;

type Gone = { kind: "ended" | "unknown" | "busy" | "offline"; message: string };

const PHASE: Record<WalkPhase, { title: string; text: string }> = {
  coming: {
    title: "Nova is coming to get you",
    text: "Your ticket shows on Nova's screen. The buttons work as soon as Nova is with you.",
  },
  pickup: { title: "Nova is here for you", text: "Tap “That's me” on Nova's screen, or say your ticket to Nova." },
  walking: { title: "Walking with you", text: "Nova keeps your pace and looks back to check on you." },
  arrived: { title: "You are there", text: "Please take a seat. The team knows you are here." },
  with_staff: { title: "With the team", text: "Nova is not needed right now. Get well soon." },
  help: { title: "Nova has stopped", text: "A person from the team is coming to help you." },
};

const ACTIONS: { action: WalkAction; label: string; hint: string; Icon: typeof HandPalm }[] = [
  { action: "wait", label: "Wait", hint: "Nova stops", Icon: HandPalm },
  { action: "resume", label: "Go on", hint: "Nova walks on", Icon: Play },
  { action: "slower", label: "Slower", hint: "A bit slower", Icon: PersonSimpleWalk },
  { action: "faster", label: "Faster", hint: "A bit faster", Icon: PersonSimpleRun },
];

const DONE_TEXT: Record<WalkAction, string> = {
  wait: "Nova got it and waits for you.",
  resume: "Nova got it and walks on.",
  slower: "Nova got it and walks slower.",
  faster: "Nova got it and walks a bit faster.",
};

// the parts of the browser speech api this page uses
type Recognition = {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
};

function speechApi(): (new () => Recognition) | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

async function call(path: string, body?: unknown) {
  const res = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    cache: "no-store",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => null);
  return { status: res.status, data };
}

export function WalkClient({ code }: { code: string }) {
  const [view, setView] = useState<WalkView | null>(null);
  const [gone, setGone] = useState<Gone | null>(null);
  const [note, setNote] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [last, setLast] = useState<WalkAction | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const stopped = useRef(false);

  const load = useCallback(async () => {
    try {
      const { status, data } = await call(`/api/walk/${encodeURIComponent(code)}`);
      if (status === 200) {
        setView(data as WalkView);
        setGone(null);
        return POLL_MS;
      }
      if (status === 410) {
        stopped.current = true;
        setGone({ kind: "ended", message: "Your walk has ended. Get well soon." });
      } else if (status === 404) {
        stopped.current = true;
        setGone({ kind: "unknown", message: "This link only works while Nova walks with you." });
      } else if (status === 429) {
        setGone({ kind: "busy", message: "Too many requests. The page tries again in a moment." });
        return 20_000;
      } else {
        setGone({ kind: "offline", message: "Can't reach Nova Clinic right now. Trying again." });
      }
    } catch {
      setGone({ kind: "offline", message: "No connection. The page tries again." });
    }
    return POLL_MS * 2;
  }, [code]);

  useEffect(() => {
    stopped.current = false;
    const tick = async () => {
      clearTimeout(timer.current);
      const wait = document.visibilityState === "visible" ? await load() : POLL_MS;
      if (!stopped.current) timer.current = setTimeout(tick, wait);
    };
    tick();
    const wake = () => document.visibilityState === "visible" && !stopped.current && tick();
    document.addEventListener("visibilitychange", wake);
    return () => {
      stopped.current = true;
      clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", wake);
    };
  }, [load]);

  const act = async (action: WalkAction) => {
    setBusy(action);
    setNote(null);
    try {
      const { status, data } = await call(`/api/walk/${encodeURIComponent(code)}`, { action });
      if (status === 201) {
        setLast(action);
        setNote({ tone: "ok", text: DONE_TEXT[action] });
      } else setNote({ tone: "error", text: (data as { error?: string } | null)?.error ?? "That did not work. Please try again." });
    } catch {
      setNote({ tone: "error", text: "No connection. Please try again." });
    }
    setBusy(null);
    await load();
  };

  const ended = gone && (gone.kind === "ended" || gone.kind === "unknown");

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col px-4 pt-5 pb-8">
      <div className="flex items-center gap-3">
        <Mascot size={40} />
        <div className="leading-tight">
          <p className="text-[17px] font-semibold tracking-tight">Nova Clinic</p>
          <p className="text-sm text-muted">Your walk with Nova</p>
        </div>
      </div>

      {ended ? (
        <section className="pop mt-10 rounded-2xl border border-line bg-surface p-6 text-center shadow-panel">
          {gone.kind === "ended" ? (
            <CheckCircle size={48} weight="fill" aria-hidden className="mx-auto text-ok" />
          ) : (
            <LinkBreak size={48} aria-hidden className="mx-auto text-muted" />
          )}
          <h1 className="mt-4 text-2xl font-semibold tracking-tight">
            {gone.kind === "ended" ? "Thank you" : "This link is not active"}
          </h1>
          <p className="mt-2 text-[17px] text-muted">{gone.message}</p>
        </section>
      ) : !view ? (
        <div className="mt-8 grid gap-4" aria-busy="true" aria-label="Loading">
          <div className="h-44 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
          <div className="grid grid-cols-2 gap-3">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="h-24 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
            ))}
          </div>
          {gone && <p className="text-[15px] text-muted">{gone.message}</p>}
        </div>
      ) : (
        <>
          <section aria-labelledby="ticket-label" className="mt-6 rounded-2xl bg-primary-soft px-5 py-5">
            <p id="ticket-label" className="text-sm font-semibold text-primary-ink">
              Your ticket
            </p>
            <p className="tabular text-[88px] leading-none font-bold tracking-tight text-primary-ink">{view.ticket}</p>
            <p className="mt-3 text-lg leading-snug font-semibold">
              Going to {view.to.name}
              {view.to.room && <span className="font-normal text-muted">, Room {view.to.room}</span>}
            </p>
            <p className="mt-1 flex items-center gap-1.5 text-sm text-muted">
              {view.wheelchair && <Wheelchair size={16} aria-hidden />}
              {PACE_LABEL[view.pace]} pace{view.wheelchair ? ", wheelchair" : ""}
            </p>
          </section>

          <section aria-live="polite" className="mt-4 rounded-2xl border border-line bg-surface p-5 shadow-panel">
            <h1 className="text-xl font-semibold tracking-tight">{PHASE[view.phase].title}</h1>
            <p className="mt-1 text-[15px] text-muted">{PHASE[view.phase].text}</p>
            {view.phase === "walking" && (view.remainingMeters !== null || view.progress !== null) && (
              <div className="mt-4">
                {view.remainingMeters !== null && (
                  <p className="text-3xl font-semibold tracking-tight tabular">
                    {view.remainingMeters < 10 ? view.remainingMeters.toFixed(1) : Math.round(view.remainingMeters)} m
                    <span className="ml-2 text-base font-normal text-muted">to go</span>
                  </p>
                )}
                {view.progress !== null && (
                  <div className="mt-3 h-2 w-full overflow-hidden rounded-full bg-subtle" aria-hidden>
                    <div className="walk-line h-full rounded-full bg-primary" style={{ width: `${Math.round(view.progress * 100)}%` }} />
                  </div>
                )}
              </div>
            )}
            {gone && <p className="mt-3 text-sm font-medium text-warn">{gone.message}</p>}
          </section>

          <section aria-label="Tell Nova" className="mt-4">
            <div className="grid grid-cols-2 gap-3">
              {ACTIONS.map(({ action, label, hint, Icon }) => {
                const on = last === action && view.canControl;
                const primary = (last === "wait" && action === "resume") || (last !== "wait" && action === "wait");
                return (
                  <button
                    key={action}
                    type="button"
                    disabled={!view.canControl || busy !== null}
                    onClick={() => act(action)}
                    aria-pressed={on}
                    className={`press flex min-h-24 flex-col items-start justify-between gap-2 rounded-2xl border p-4 text-left disabled:cursor-not-allowed disabled:opacity-45 ${
                      on
                        ? "border-primary bg-primary text-on-primary"
                        : primary
                          ? "border-primary bg-surface text-ink ring-1 ring-primary"
                          : "border-line-strong bg-surface text-ink"
                    }`}
                  >
                    <Icon size={30} weight={on ? "fill" : "regular"} aria-hidden />
                    <span>
                      <span className="block text-lg leading-tight font-semibold">{busy === action ? "Sending" : label}</span>
                      <span className={`block text-sm ${on ? "text-on-primary/80" : "text-muted"}`}>{hint}</span>
                    </span>
                  </button>
                );
              })}
            </div>
            <Talk code={code} disabled={!view.canControl} onSent={(text) => setNote({ tone: "ok", text })} onError={(text) => setNote({ tone: "error", text })} />
            <p className="mt-3 min-h-6 text-[15px]" role="status">
              {note && <span className={note.tone === "ok" ? "font-medium text-ok" : "font-medium text-danger"}>{note.text}</span>}
            </p>
          </section>
        </>
      )}

      <p className="mt-auto flex items-start gap-2 pt-8 text-sm text-muted">
        <ShieldCheck size={18} aria-hidden className="mt-0.5 shrink-0" />
        {ended
          ? "Nova only knows your ticket and the room. Nothing is recorded."
          : "Nova only knows your ticket and the room. Nothing is recorded. What you say here goes to Nova as text and is deleted once Nova has it."}
      </p>
    </main>
  );
}

function Talk({
  code,
  disabled,
  onSent,
  onError,
}: {
  code: string;
  disabled: boolean;
  onSent: (text: string) => void;
  onError: (text: string) => void;
}) {
  const [mode, setMode] = useState<"idle" | "listening" | "review" | "sending">("idle");
  const [text, setText] = useState("");
  const [canListen, setCanListen] = useState(false);
  const rec = useRef<Recognition | null>(null);

  useEffect(() => {
    setCanListen(speechApi() !== null);
    return () => rec.current?.stop();
  }, []);

  const listen = () => {
    const Api = speechApi();
    if (!Api) {
      setMode("review");
      return;
    }
    const r = new Api();
    // the phone's own language: nova answers in the language the patient speaks
    r.lang = navigator.language;
    r.interimResults = true;
    r.continuous = false;
    r.onresult = (e) => {
      setText(
        Array.from(e.results)
          .map((x) => x[0]?.transcript ?? "")
          .join("")
          .trim(),
      );
    };
    r.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed") onError("The microphone is blocked. You can type instead.");
      setMode("review");
    };
    r.onend = () => setMode((m) => (m === "listening" ? "review" : m));
    rec.current = r;
    setText("");
    setMode("listening");
    try {
      r.start();
    } catch {
      setMode("review");
    }
  };

  const send = async () => {
    const t = text.trim();
    if (!t) return;
    setMode("sending");
    try {
      const res = await fetch(`/api/walk/${encodeURIComponent(code)}/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: t }),
      });
      const data = await res.json().catch(() => null);
      if (res.status === 201) {
        setText("");
        setMode("idle");
        onSent("Nova got your message and answers out loud.");
        return;
      }
      onError((data as { error?: string } | null)?.error ?? "That did not work. Please try again.");
    } catch {
      onError("No connection. Please try again.");
    }
    setMode("review");
  };

  if (mode === "idle") {
    return (
      <button
        type="button"
        disabled={disabled}
        onClick={listen}
        className="press mt-3 flex h-16 w-full items-center justify-center gap-3 rounded-2xl bg-primary text-lg font-semibold text-on-primary hover:bg-primary-hover disabled:cursor-not-allowed disabled:opacity-45"
      >
        <Microphone size={26} weight="fill" aria-hidden />
        {canListen ? "Talk to Nova" : "Write to Nova"}
      </button>
    );
  }

  return (
    <div className="mt-3 rounded-2xl border border-primary bg-surface p-4">
      {mode === "listening" ? (
        <div className="flex items-center gap-3">
          <span className="listening flex size-12 shrink-0 items-center justify-center rounded-full bg-primary text-on-primary">
            <Microphone size={24} weight="fill" aria-hidden />
          </span>
          <p className="min-w-0 flex-1 text-[17px]" aria-live="polite">
            {text || <span className="text-muted">Listening. Say what you need.</span>}
          </p>
        </div>
      ) : (
        <>
          <label htmlFor="talk-text" className="text-sm font-semibold">
            Your message to Nova
          </label>
          <textarea
            id="talk-text"
            value={text}
            onChange={(e) => setText(e.target.value)}
            maxLength={300}
            rows={2}
            autoFocus={!text}
            placeholder="For example: where are the toilets?"
            className="mt-2 w-full resize-none rounded-xl border border-line-strong bg-surface px-3 py-2 text-[17px] placeholder:text-muted focus:border-primary focus:outline-none"
          />
        </>
      )}
      <div className="mt-3 grid grid-cols-2 gap-2">
        <button
          type="button"
          onClick={() => {
            rec.current?.stop();
            setText("");
            setMode("idle");
          }}
          className="press h-14 rounded-xl border border-line-strong bg-surface text-base font-semibold text-ink hover:bg-subtle"
        >
          Cancel
        </button>
        {mode === "listening" ? (
          <button
            type="button"
            onClick={() => rec.current?.stop()}
            className="press h-14 rounded-xl bg-primary text-base font-semibold text-on-primary hover:bg-primary-hover"
          >
            Done
          </button>
        ) : (
          <button
            type="button"
            onClick={send}
            disabled={!text.trim() || mode === "sending"}
            className="press flex h-14 items-center justify-center gap-2 rounded-xl bg-primary text-base font-semibold text-on-primary hover:bg-primary-hover disabled:opacity-45"
          >
            <PaperPlaneRight size={20} weight="fill" aria-hidden />
            {mode === "sending" ? "Sending" : "Send"}
          </button>
        )}
      </div>
    </div>
  );
}
