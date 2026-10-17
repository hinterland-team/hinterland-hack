// demo mode on the server (docs/demo-mode.md): the script from Settings -> Demo script (kept in the store, the
// default until someone saves one), and the command { type: "demo", op, script?, text? } for otto.
// a start always carries the saved script. robot:demo:<robot> remembers that the dashboard started demo mode
import { now } from "./common";
import { DEMO_OPS, type DemoOp, type DemoScript, checkScript, defaultScript } from "./demo-script";
import { ApiError, text } from "./http";
import { getStore } from "./store";
import type { RobotCommand } from "./types";

const SCRIPT_KEY = "demo:script";
const flagKey = (robot: string) => `robot:demo:${robot}`;

export type ScriptInfo = { script: DemoScript; isDefault: boolean; updatedAt: string | null };

export async function readScript(): Promise<ScriptInfo> {
  const raw = await getStore().getSetting(SCRIPT_KEY);
  if (raw) {
    try {
      const v = JSON.parse(raw) as { script?: unknown; updatedAt?: unknown };
      const { script, errors } = checkScript(v.script);
      if (!Object.keys(errors).length) return { script, isDefault: false, updatedAt: typeof v.updatedAt === "string" ? v.updatedAt : null };
    } catch {
      // broken: the default
    }
  }
  return { script: defaultScript(), isDefault: true, updatedAt: null };
}

// staff: the whole script. a wrong one is refused with the first problem (the editor shows each one)
export async function saveScript(raw: unknown): Promise<ScriptInfo> {
  const { script, errors } = checkScript(raw);
  const first = Object.entries(errors)[0];
  if (first) throw new ApiError(400, first[0] === "script" ? first[1] : `${first[0]}: ${first[1]}`);
  const updatedAt = now();
  await getStore().setSetting(SCRIPT_KEY, JSON.stringify({ script, updatedAt }));
  return { script, isDefault: false, updatedAt };
}

// back to the default script of docs/demo-mode.md (an empty setting = the default)
export async function resetScript(): Promise<ScriptInfo> {
  await getStore().setSetting(SCRIPT_KEY, "");
  return readScript();
}

export async function demoFlag(robot: string) {
  const raw = await getStore().getSetting(flagKey(robot));
  try {
    return raw ? (JSON.parse(raw) as { on?: unknown }).on === true : false;
  } catch {
    return false;
  }
}

async function setDemoFlag(robot: string, on: boolean) {
  if ((await demoFlag(robot)) === on) return;
  await getStore().setSetting(flagKey(robot), JSON.stringify({ on, at: now() }));
}

const isOp = (v: unknown): v is DemoOp => typeof v === "string" && (DEMO_OPS as readonly string[]).includes(v);

// the demo command for otto. start: with the saved script, demo mode on; stop: off. a new start / stop closes an
// older start / stop otto has not fetched yet (the newest choice counts)
export async function queueDemo(robot: string, op: unknown, sayText?: unknown): Promise<RobotCommand> {
  if (!isOp(op)) throw new ApiError(400, `op must be one of ${DEMO_OPS.join(", ")}`);
  const store = getStore();
  const at = now();
  const id = crypto.randomUUID();
  if (op === "say") {
    const t = text(sayText, 300);
    if (!t) throw new ApiError(400, "text is required: what Otto should say");
    return store.createCommand({ id, robot, type: "demo", op, text: t, createdAt: at });
  }
  if (op === "start" || op === "stop") {
    for (const c of await store.listCommands(robot, 30)) {
      if (c.type === "demo" && (c.op === "start" || c.op === "stop") && !c.deliveredAt && !c.doneAt) await store.finishCommand(c.id, at);
    }
    await setDemoFlag(robot, op === "start");
  }
  if (op === "start") {
    const { script } = await readScript();
    return store.createCommand({ id, robot, type: "demo", op, script, createdAt: at });
  }
  return store.createCommand({ id, robot, type: "demo", op, createdAt: at });
}

// lists for the pages: a start's script stays out (it is on Settings -> Demo script and in otto's copy)
export function withoutScript(c: RobotCommand): RobotCommand {
  if (c.type !== "demo" || !c.script) return c;
  const { script: _s, ...rest } = c;
  void _s;
  return rest;
}
