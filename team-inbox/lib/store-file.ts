// laptop mode (no DATABASE_URL): the memory store, plus a copy of its whole data object in a json file
// (STORE_FILE, default .data/store.json). read once when the server starts, written after changes:
// debounced, to a temp file first and then renamed over the old one, so a crash never leaves half a file.
// journeys keep their 12 h retention: the purge runs right after loading, and on every write as before
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeSync } from "node:fs";
import { open, rename } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { memoryData, restoreMemoryData } from "./store-memory";
import type { PurgeCutoffs, Store } from "./store-types";

export const FILE_VERSION = 1;
// places, channels, settings, journeys: saved shortly after the last change
export const SAVE_DEBOUNCE_MS = 800;
export const SAVE_MAX_WAIT_MS = 4000;
// what the robot sends all the time (polls, heartbeats, map frames, logs): saved less often
export const SLOW_DEBOUNCE_MS = 5000;
export const SLOW_MAX_WAIT_MS = 15000;

// methods that only read. every other store method may change the data and plans a save
const READS = /^(list|get|count|find|used)/;
const SLOW = new Set(["touchRobot", "saveHeartbeat", "saveMap", "addLogs", "addFeedItems", "addHit", "addLoginFailure", "clearLoginFailures", "purge"]);

export type FileInfo = { file: string; loaded: boolean; savedAt: string | null; writes: number; error: string | null };

type State = {
  file: string;
  timer?: ReturnType<typeof setTimeout>;
  dirtySince: number | null;
  deadline: number;
  saving: Promise<void> | null;
  writes: number;
  savedAt: string | null;
  loaded: boolean;
  error: string | null;
  exitHook: boolean;
};

// one per server process, also across hot reloads in dev
const g = globalThis as unknown as { __ottoStoreFile?: State };

// "off", "none", "false" or "0" = memory only
export function storeFilePath(env: Record<string, string | undefined> = process.env): string | null {
  const raw = env.STORE_FILE?.trim();
  if (raw && /^(off|none|false|0)$/i.test(raw)) return null;
  return resolve(process.cwd(), raw || ".data/store.json");
}

function sleepSync(ms: number) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function body() {
  return JSON.stringify({ version: FILE_VERSION, savedAt: new Date().toISOString(), data: memoryData() });
}

// windows: an open handle (virus scanner, indexer) can block the rename for a moment
const BUSY = new Set(["EPERM", "EBUSY", "EACCES"]);

function writeSyncNow(s: State) {
  mkdirSync(dirname(s.file), { recursive: true });
  const tmp = `${s.file}.${process.pid}.exit.tmp`;
  const fd = openSync(tmp, "w");
  try {
    writeSync(fd, body());
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  for (let i = 0; ; i++) {
    try {
      renameSync(tmp, s.file);
      break;
    } catch (err) {
      if (i >= 5 || !BUSY.has((err as { code?: string }).code ?? "")) throw err;
      sleepSync(40 * (i + 1));
    }
  }
}

async function writeAsyncNow(s: State) {
  mkdirSync(dirname(s.file), { recursive: true });
  const tmp = `${s.file}.${process.pid}.tmp`;
  const fh = await open(tmp, "w");
  try {
    await fh.writeFile(body());
    await fh.sync();
  } finally {
    await fh.close();
  }
  for (let i = 0; ; i++) {
    try {
      await rename(tmp, s.file);
      break;
    } catch (err) {
      if (i >= 5 || !BUSY.has((err as { code?: string }).code ?? "")) throw err;
      await new Promise((r) => setTimeout(r, 40 * (i + 1)));
    }
  }
}

function saved(s: State) {
  s.writes += 1;
  s.savedAt = new Date().toISOString();
  s.error = null;
}

function failed(s: State, err: unknown) {
  s.error = err instanceof Error ? err.message : "could not write the file";
  console.error(`store file: could not save ${s.file}: ${s.error}`);
}

async function flush(s: State) {
  // a save is running: wait for it, then save again if something changed meanwhile
  if (s.saving) await s.saving;
  if (s.dirtySince === null) return;
  s.dirtySince = null;
  clearTimeout(s.timer);
  s.timer = undefined;
  s.saving = writeAsyncNow(s)
    .then(() => saved(s))
    .catch((err) => failed(s, err))
    .finally(() => {
      s.saving = null;
    });
  await s.saving;
}

function fire(s: State) {
  s.timer = undefined;
  flush(s).catch(() => undefined);
}

function schedule(s: State, slow: boolean) {
  const now = Date.now();
  if (s.dirtySince === null) {
    s.dirtySince = now;
    s.deadline = now + (slow ? SLOW_MAX_WAIT_MS : SAVE_MAX_WAIT_MS);
  }
  if (slow) {
    // robot traffic never pushes a planned save further away
    if (s.timer) return;
    s.timer = setTimeout(() => fire(s), Math.max(0, Math.min(SLOW_DEBOUNCE_MS, s.deadline - now)));
    return;
  }
  s.deadline = Math.min(s.deadline, now + SAVE_MAX_WAIT_MS);
  clearTimeout(s.timer);
  s.timer = setTimeout(() => fire(s), Math.max(0, Math.min(SAVE_DEBOUNCE_MS, s.deadline - now)));
}

// last chance when the server stops (ctrl+c: next calls process.exit, which runs this). sync only here
function flushOnExit() {
  const s = g.__ottoStoreFile;
  if (!s || s.dirtySince === null) return;
  try {
    writeSyncNow(s);
    s.dirtySince = null;
    saved(s);
  } catch (err) {
    failed(s, err);
  }
}

// reads the file into the memory store (only the first time in this process: a hot reload keeps the live data)
function load(s: State, store: Store, cutoffs: () => PurgeCutoffs, resetSettings: Record<string, string>) {
  if (!existsSync(s.file)) return;
  let parsed: { version?: unknown; data?: unknown } | null = null;
  try {
    parsed = JSON.parse(readFileSync(s.file, "utf8"));
  } catch (err) {
    const keep = `${s.file}.broken-${Date.now()}`;
    try {
      renameSync(s.file, keep);
    } catch {
      // leave it where it is
    }
    console.error(`store file: ${s.file} could not be read (${err instanceof Error ? err.message : "bad json"}), starting empty. kept it as ${keep}`);
    return;
  }
  if (!parsed || parsed.version !== FILE_VERSION || !parsed.data || typeof parsed.data !== "object") {
    console.error(`store file: ${s.file} has an unknown format, starting empty`);
    return;
  }
  restoreMemoryData(parsed.data);
  const data = memoryData();
  for (const [key, value] of Object.entries(resetSettings)) if (key in data.settings) data.settings[key] = value;
  s.loaded = true;
  // finished journeys older than 12 h go right away, not only on the next write
  store
    .purge(cutoffs())
    .then(() => schedule(s, false))
    .catch(() => undefined);
}

export function fileInfo(): FileInfo | null {
  const s = g.__ottoStoreFile;
  return s ? { file: s.file, loaded: s.loaded, savedAt: s.savedAt, writes: s.writes, error: s.error } : null;
}

// for tests: write what is pending now
export async function flushStoreFile() {
  const s = g.__ottoStoreFile;
  if (s) await flush(s);
}

// wraps the memory store: every method that changes something plans a save of the whole data object.
// resetSettings: values that go back to a default after a restart (the demo switch for patients' words)
export function withFile(
  store: Store,
  file: string,
  opts: { cutoffs: () => PurgeCutoffs; resetSettings?: Record<string, string> },
): Store {
  let s = g.__ottoStoreFile;
  if (!s || s.file !== file) {
    s = { file, dirtySince: null, deadline: 0, saving: null, writes: 0, savedAt: null, loaded: false, error: null, exitHook: false };
    g.__ottoStoreFile = s;
    load(s, store, opts.cutoffs, opts.resetSettings ?? {});
  }
  if (!s.exitHook) {
    s.exitHook = true;
    process.once("exit", flushOnExit);
  }
  const state = s;
  return new Proxy(store, {
    get(target, prop, receiver) {
      const value = Reflect.get(target, prop, receiver);
      if (typeof value !== "function" || typeof prop !== "string" || READS.test(prop)) return value;
      return async (...args: unknown[]) => {
        const out = await (value as (...a: unknown[]) => unknown).apply(target, args);
        // a poll that found nothing changed nothing
        if (prop === "takeNextCommand" && !out) return out;
        schedule(state, SLOW.has(prop));
        return out;
      };
    },
  });
}
