"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { LoginNeeded, api } from "@/lib/ui";

// polls a json url. the delay can depend on the last answer (fast while nova moves).
// pauses while the tab is hidden and catches up when it comes back
export function usePoll<T>(url: string | null, delay: (data: T | null) => number) {
  const [data, setData] = useState<T | null>(null);
  const [failed, setFailed] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const delayRef = useRef(delay);
  const last = useRef<T | null>(null);

  useEffect(() => {
    delayRef.current = delay;
  });

  const load = useCallback(async () => {
    if (!url) return;
    try {
      const d = await api<T>(url);
      last.current = d;
      setData(d);
      setFailed(false);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setFailed(true);
    }
  }, [url]);

  useEffect(() => {
    if (!url) return;
    let stopped = false;
    const tick = async () => {
      clearTimeout(timer.current);
      if (document.visibilityState === "visible") await load();
      if (!stopped) timer.current = setTimeout(tick, delayRef.current(last.current));
    };
    tick();
    const wake = () => document.visibilityState === "visible" && tick();
    document.addEventListener("visibilitychange", wake);
    return () => {
      stopped = true;
      clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", wake);
    };
  }, [url, load]);

  return { data, failed, reload: load };
}

// a clock for "2 s ago" texts
export function useNow(every = 1000) {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), every);
    return () => clearInterval(t);
  }, [every]);
  return now;
}
