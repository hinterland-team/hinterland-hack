"use client";

import { ArrowClockwise, Check, Copy, MagnifyingGlass, Pause, Play, TerminalWindow, X } from "@phosphor-icons/react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LOG_SOURCES, type LogLevel, type LogLine } from "@/lib/types";
import { LoginNeeded, PAGE, ROBOT, api } from "@/lib/ui";
import { Button, Segmented } from "../components/button";

const TAIL_MS = 3000;
const PAGE_SIZE = 300;
const KEEP = 2000;

const LEVEL_OPTIONS: { value: LogLevel; label: string }[] = [
  { value: "debug", label: "All" },
  { value: "info", label: "Info" },
  { value: "warn", label: "Warning" },
  { value: "error", label: "Error" },
];

const LEVEL_TONE: Record<LogLevel, string> = {
  debug: "bg-subtle text-muted",
  info: "bg-primary-soft text-primary-ink",
  warn: "bg-warn-soft text-warn",
  error: "bg-danger-soft text-danger",
};

function time(iso: string) {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" });
}

function asText(lines: LogLine[]) {
  return lines
    .map((l) => `${new Date(l.at).toISOString()} ${l.level.toUpperCase().padEnd(5)} ${l.source.padEnd(6)} ${l.text}`)
    .join("\n");
}

// the clipboard api needs https or localhost. on the lan (plain http) the old way still works
async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    return true;
  } catch {
    const area = document.createElement("textarea");
    area.value = value;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  }
}

function Chip({ on, onClick, children, mono = false }: { on: boolean; onClick: () => void; children: React.ReactNode; mono?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={`press h-10 rounded-full border px-4 text-sm ${mono ? "font-mono font-medium" : "font-semibold"} ${
        on ? "border-primary bg-primary-soft text-primary-ink" : "border-line text-muted hover:border-line-strong hover:text-ink"
      }`}
    >
      {children}
    </button>
  );
}

export function LogsClient({
  initialLevel,
  initialSources,
  initialQuery,
}: {
  initialLevel: LogLevel;
  initialSources: string[];
  initialQuery: string;
}) {
  const [level, setLevel] = useState<LogLevel>(initialLevel);
  const [sources, setSources] = useState<string[]>(initialSources);
  const [input, setInput] = useState(initialQuery);
  const [query, setQuery] = useState(initialQuery);
  const [lines, setLines] = useState<LogLine[] | null>(null);
  const [waiting, setWaiting] = useState<LogLine[]>([]);
  const [paused, setPaused] = useState(false);
  const [more, setMore] = useState(false);
  const [failed, setFailed] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);
  const [olderBusy, setOlderBusy] = useState(false);
  // lines above this id arrived after the first load: they get a short highlight
  const [freshFrom, setFreshFrom] = useState(Infinity);
  const lastId = useRef<number | null>(null);
  const pausedRef = useRef(paused);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  // typing settles for 300 ms before it filters
  useEffect(() => {
    const t = setTimeout(() => setQuery(input.trim()), 300);
    return () => clearTimeout(t);
  }, [input]);

  const filters = useMemo(() => {
    const p = new URLSearchParams({ robot: ROBOT });
    if (level !== "debug") p.set("level", level);
    if (sources.length) p.set("source", sources.join(","));
    if (query) p.set("q", query);
    return p.toString();
  }, [level, sources, query]);

  // the address keeps the filters, so a link like /logs?level=error opens the same view
  useEffect(() => {
    const p = new URLSearchParams(filters);
    p.delete("robot");
    const rest = p.toString();
    const next = `/logs${rest ? `?${rest}` : ""}`;
    if (next !== window.location.pathname + window.location.search) window.history.replaceState(null, "", next);
  }, [filters]);

  // new filters: load the newest page again
  useEffect(() => {
    let off = false;
    setLines(null);
    setWaiting([]);
    lastId.current = null;
    (async () => {
      try {
        const res = await api<{ lines: LogLine[]; more: boolean }>(`/api/robot/logs?${filters}&limit=${PAGE_SIZE}`);
        if (off) return;
        const last = res.lines.at(-1)?.id ?? 0;
        setLines(res.lines);
        setMore(res.more);
        setFailed(false);
        setFreshFrom(last);
        lastId.current = last;
      } catch (e) {
        if (!off && !(e instanceof LoginNeeded)) {
          setFailed(true);
          setLines([]);
        }
      }
    })();
    return () => {
      off = true;
    };
  }, [filters]);

  // live tail every 3 s. while paused the new lines wait and are counted
  const tail = useCallback(async () => {
    if (lastId.current === null || document.visibilityState !== "visible") return;
    try {
      const res = await api<{ lines: LogLine[] }>(`/api/robot/logs?${filters}&after=${lastId.current}&limit=500`);
      setFailed(false);
      if (!res.lines.length) return;
      lastId.current = res.lines.at(-1)!.id;
      if (pausedRef.current) setWaiting((w) => [...w, ...res.lines].slice(-KEEP));
      else setLines((l) => [...(l ?? []), ...res.lines].slice(-KEEP));
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setFailed(true);
    }
  }, [filters]);

  useEffect(() => {
    const t = setInterval(tail, TAIL_MS);
    return () => clearInterval(t);
  }, [tail]);

  const resume = () => {
    setLines((l) => [...(l ?? []), ...waiting].slice(-KEEP));
    setWaiting([]);
    setPaused(false);
  };

  const older = async () => {
    const first = lines?.[0]?.id;
    if (!first) return;
    setOlderBusy(true);
    try {
      const res = await api<{ lines: LogLine[]; more: boolean }>(`/api/robot/logs?${filters}&before=${first}&limit=${PAGE_SIZE}`);
      setLines((l) => [...res.lines, ...(l ?? [])]);
      setMore(res.more);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setFailed(true);
    }
    setOlderBusy(false);
  };

  const copy = async () => {
    const list = lines ?? [];
    const ok = await copyText(asText(list));
    setCopied(ok ? `Copied ${list.length} ${list.length === 1 ? "line" : "lines"}` : "Copy did not work");
    setTimeout(() => setCopied(null), 2500);
  };

  const toggleSource = (s: string) => setSources((list) => (list.includes(s) ? list.filter((x) => x !== s) : [...list, s]));
  const filtered = level !== "debug" || sources.length > 0 || query !== "";
  const newest = lines ? [...lines].reverse() : [];

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Logs</h1>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            What Otto&apos;s app (Nova Clinic) reports. Kept 24 hours, at most 5000 lines. Never what patients say.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          {paused ? (
            <Button kind="primary" onClick={resume}>
              <Play size={18} weight="fill" aria-hidden />
              {waiting.length ? `Resume, ${waiting.length} new` : "Resume"}
            </Button>
          ) : (
            <Button onClick={() => setPaused(true)}>
              <Pause size={18} weight="fill" aria-hidden /> Pause
            </Button>
          )}
          <Button onClick={copy} disabled={!lines?.length}>
            {copied ? <Check size={18} aria-hidden /> : <Copy size={18} aria-hidden />}
            {copied ?? "Copy"}
          </Button>
        </div>
      </div>

      <section
        aria-label="Filters"
        className="grid gap-4 rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5 lg:grid-cols-2"
      >
        <div className="min-w-0">
          <label htmlFor="log-search" className="block text-sm font-semibold">
            Search
          </label>
          <div className="relative mt-2">
            <MagnifyingGlass
              size={20}
              aria-hidden
              className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2 text-muted"
            />
            <input
              id="log-search"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              maxLength={100}
              autoComplete="off"
              spellCheck={false}
              placeholder="Words in the log line"
              className="h-12 w-full rounded-xl border border-line-strong bg-surface pr-11 pl-11 text-base placeholder:text-muted focus:border-primary focus:outline-none"
            />
            {input && (
              <button
                type="button"
                onClick={() => setInput("")}
                aria-label="Clear the search"
                className="absolute top-1/2 right-1.5 flex size-9 -translate-y-1/2 items-center justify-center rounded-lg text-muted hover:bg-subtle hover:text-ink"
              >
                <X size={18} aria-hidden />
              </button>
            )}
          </div>
        </div>
        <div className="min-w-0">
          <p className="text-sm font-semibold">
            Level <span className="font-normal text-muted">(and above)</span>
          </p>
          <div className="mt-2">
            <Segmented label="Level and above" value={level} onChange={setLevel} options={LEVEL_OPTIONS} />
          </div>
        </div>
        <div className="min-w-0 lg:col-span-2">
          <p className="text-sm font-semibold">Source</p>
          <div className="mt-2 flex flex-wrap gap-2" role="group" aria-label="Sources">
            <Chip on={sources.length === 0} onClick={() => setSources([])}>
              All
            </Chip>
            {LOG_SOURCES.map((s) => (
              <Chip key={s} mono on={sources.includes(s)} onClick={() => toggleSource(s)}>
                {s}
              </Chip>
            ))}
          </div>
        </div>
      </section>

      <div className="mt-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-1 text-sm text-muted" role="status">
        <span className="tabular">
          {lines === null
            ? "Loading"
            : `${lines.length} ${lines.length === 1 ? "line" : "lines"}${filtered ? " match" : ""}, newest first`}
        </span>
        <span>
          {failed ? (
            <span className="font-medium text-danger">Can&apos;t reach the server, retrying</span>
          ) : paused ? (
            "Paused"
          ) : (
            "Live, every 3 seconds"
          )}
        </span>
      </div>

      <section aria-label="Log lines" className="mt-2 overflow-hidden rounded-2xl border border-line bg-surface shadow-panel">
        {lines === null ? (
          <div className="grid gap-2 p-4" aria-busy="true">
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-6 animate-pulse rounded-md bg-subtle motion-reduce:animate-none" />
            ))}
          </div>
        ) : newest.length === 0 ? (
          <div className="flex flex-col items-start gap-3 p-6">
            <TerminalWindow size={32} aria-hidden className="text-muted" />
            <p className="max-w-prose text-[15px] text-muted">
              {filtered
                ? "No lines match these filters."
                : "No log lines yet. Otto's app sends its log here while it runs. New lines show up within 3 seconds."}
            </p>
            {filtered && (
              <Button
                size="sm"
                onClick={() => {
                  setLevel("debug");
                  setSources([]);
                  setInput("");
                }}
              >
                Show all lines
              </Button>
            )}
          </div>
        ) : (
          <ol className="divide-y divide-line">
            {newest.map((l) => (
              <li
                key={l.id}
                className={`grid grid-cols-[auto_auto_minmax(0,1fr)] items-baseline gap-x-3 gap-y-1 px-4 py-2 sm:grid-cols-[76px_56px_52px_minmax(0,1fr)] sm:px-5 ${
                  l.id > freshFrom ? "rise" : ""
                } ${l.level === "error" ? "bg-danger-soft/40" : ""}`}
              >
                <span className="font-mono text-xs text-muted tabular sm:text-[13px]">{time(l.at)}</span>
                <span
                  className={`justify-self-start rounded-md px-1.5 font-mono text-xs leading-5 font-semibold ${LEVEL_TONE[l.level]}`}
                >
                  {l.level}
                </span>
                <span className="font-mono text-xs text-muted sm:text-[13px]">{l.source}</span>
                <span className="col-span-3 font-mono text-[13px] leading-5 break-words sm:col-span-1">{l.text}</span>
              </li>
            ))}
          </ol>
        )}
        {more && lines && lines.length > 0 && (
          <div className="border-t border-line p-3">
            <Button kind="ghost" size="sm" onClick={older} disabled={olderBusy}>
              <ArrowClockwise size={16} aria-hidden /> {olderBusy ? "Loading" : "Load older lines"}
            </Button>
          </div>
        )}
      </section>
    </main>
  );
}
