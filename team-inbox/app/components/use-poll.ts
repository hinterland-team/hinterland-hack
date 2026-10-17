"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { LoginNeeded, goToLogin } from "@/lib/ui";

export type PollOptions<T> = {
  // ms to the next poll, can depend on the last answer (fast while otto moves)
  every: number | ((data: T | null) => number);
  // ms between polls while the tab is hidden. null = no polls at all while hidden (the default)
  hiddenEvery?: number | null;
  // keep unchanged parts of the answer as the same objects, so memoized panels skip re-rendering
  share?: boolean;
};

// one level deep: a part that did not change stays the old object
function shareParts<T>(old: T | null, next: T): T {
  if (!old || typeof old !== "object" || typeof next !== "object" || next === null || Array.isArray(next)) return next;
  const before = old as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  let same = true;
  for (const [key, value] of Object.entries(next as Record<string, unknown>)) {
    const prev = before[key];
    if (prev !== undefined && (prev === value || JSON.stringify(prev) === JSON.stringify(value))) out[key] = prev;
    else {
      out[key] = value;
      same = false;
    }
  }
  return same && Object.keys(before).length === Object.keys(out).length ? old : (out as T);
}

// polls a json url. pauses while the tab is hidden (or polls slowly with hiddenEvery) and catches up when it comes
// back. sends the last ETag: a 304 changes nothing, so nothing re-renders. only one loop per hook, ever
export function usePoll<T>(url: string | null, opts: PollOptions<T>) {
  const [data, setData] = useState<T | null>(null);
  const [failed, setFailed] = useState(false);
  const optsRef = useRef(opts);
  const etag = useRef<string | null>(null);
  const last = useRef<T | null>(null);
  const seq = useRef(0);
  const applied = useRef(0);

  useEffect(() => {
    optsRef.current = opts;
  });

  // a new url starts empty
  useEffect(() => {
    etag.current = null;
    last.current = null;
    setData(null);
  }, [url]);

  const fetchOnce = useCallback(
    async (signal?: AbortSignal) => {
      if (!url) return;
      const mine = ++seq.current;
      try {
        const res = await fetch(url, {
          cache: "no-store",
          headers: etag.current ? { "If-None-Match": etag.current } : undefined,
          signal,
        });
        if (res.status === 401) goToLogin();
        if (res.status === 304) {
          setFailed(false);
          return;
        }
        if (!res.ok) throw new Error(`request failed (${res.status})`);
        const body = (await res.json()) as T;
        // an older answer that arrives after a newer one is dropped
        if (mine < applied.current) return;
        applied.current = mine;
        etag.current = res.headers.get("ETag");
        const next = optsRef.current.share ? shareParts(last.current, body) : body;
        last.current = next;
        setData(next);
        setFailed(false);
      } catch (e) {
        if (e instanceof LoginNeeded || (e instanceof DOMException && e.name === "AbortError")) return;
        setFailed(true);
      }
    },
    [url],
  );

  useEffect(() => {
    if (!url) return;
    let stopped = false;
    let busy = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();

    const delay = () => {
      const o = optsRef.current;
      if (document.visibilityState === "hidden") return o.hiddenEvery ?? null;
      return typeof o.every === "function" ? o.every(last.current) : o.every;
    };
    const plan = () => {
      clearTimeout(timer);
      const d = delay();
      if (!stopped && d !== null) timer = setTimeout(tick, d);
    };
    const tick = async () => {
      if (stopped || busy) return;
      busy = true;
      await fetchOnce(controller.signal);
      busy = false;
      plan();
    };
    const onVisibility = () => {
      if (stopped) return;
      if (document.visibilityState === "visible") {
        clearTimeout(timer);
        // a running poll plans the next one itself
        if (!busy) tick();
      } else {
        plan();
      }
    };

    tick();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      stopped = true;
      clearTimeout(timer);
      controller.abort();
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [url, fetchOnce]);

  const reload = useCallback(() => fetchOnce(), [fetchOnce]);
  return { data, failed, reload };
}

// a clock for "2 s ago" texts. ticks only while the tab is visible
export function useNow(every = 1000) {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    const tick = () => {
      if (document.visibilityState === "visible") setNow(Date.now());
    };
    setNow(Date.now());
    const t = setInterval(tick, every);
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(t);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [every]);
  return now;
}
