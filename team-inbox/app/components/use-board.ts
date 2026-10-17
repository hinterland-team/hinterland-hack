"use client";

import { useCallback, useState } from "react";
import type { Board } from "@/lib/board";
import { LoginNeeded, ROBOT } from "@/lib/ui";
import { useAlertsOn } from "./staff-alerts";
import { usePoll } from "./use-poll";

// polls /api/board (304 while nothing changed). pauses while the tab is hidden, unless alerts are on for this
// device: then it looks every 15 s, so "A17 has arrived" still comes
export function useBoard(every = 2000, robot = ROBOT) {
  const alerts = useAlertsOn();
  const { data, failed, reload } = usePoll<Board>(`/api/board?robot=${encodeURIComponent(robot)}`, {
    every,
    hiddenEvery: alerts ? 15_000 : null,
    share: true,
  });
  return { data, offline: failed, reload };
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
  const clearError = useCallback(() => setError(null), []);
  return { run, busy, error, clearError };
}
