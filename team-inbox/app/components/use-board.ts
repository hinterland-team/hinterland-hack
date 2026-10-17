"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { Board } from "@/lib/board";
import { LoginNeeded, ROBOT, api } from "@/lib/ui";

// polls /api/board. pauses while the tab is hidden, catches up when it comes back
export function useBoard(every = 2000) {
  const [data, setData] = useState<Board | null>(null);
  const [offline, setOffline] = useState(false);
  const [now, setNow] = useState<number | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const stopped = useRef(false);

  const load = useCallback(async () => {
    try {
      const b = await api<Board>(`/api/board?robot=${ROBOT}`);
      setData(b);
      setOffline(false);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setOffline(true);
    }
  }, []);

  useEffect(() => {
    stopped.current = false;
    const tick = async () => {
      clearTimeout(timer.current);
      if (document.visibilityState === "visible") await load();
      if (!stopped.current) timer.current = setTimeout(tick, every);
    };
    tick();
    const wake = () => document.visibilityState === "visible" && tick();
    document.addEventListener("visibilitychange", wake);
    return () => {
      stopped.current = true;
      clearTimeout(timer.current);
      document.removeEventListener("visibilitychange", wake);
    };
  }, [load, every]);

  useEffect(() => {
    setNow(Date.now());
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  return { data, offline, now, reload: load };
}

// runs an action, shows its error, reloads the board
export function useAction(reload: () => Promise<void>) {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const run = useCallback(
    async (key: string, fn: () => Promise<unknown>) => {
      setBusy(key);
      try {
        await fn();
        setError(null);
      } catch (e) {
        if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "That did not work");
      }
      setBusy(null);
      await reload();
    },
    [reload],
  );
  return { run, busy, error, clearError: () => setError(null) };
}
