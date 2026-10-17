// demo mode (docs/demo-mode.md): the rehearsed script for the judges, the heartbeat's demo object and the
// timings. shared by the server, the pages and virtual otto. no imports at runtime, tests load this file directly

export const DEMO_PARTS = ["reception", "firstCheck", "fellBack", "arrive", "obstacle", "detour"] as const;
export type DemoPart = (typeof DEMO_PARTS)[number];

export const DEMO_ACTIONS = ["check_in", "pace", "wait_close"] as const;
export type DemoAction = (typeof DEMO_ACTIONS)[number];

export const DEMO_PACES = ["normal", "slow", "very_slow"] as const;
export type DemoPace = (typeof DEMO_PACES)[number];

export type DemoStep =
  | { who: "otto"; text: string; waitS?: number }
  | { who: "patient"; text: string; waitS?: number }
  | { who: "action"; do: "check_in" | "wait_close" }
  | { who: "action"; do: "pace"; value: DemoPace };

export type DemoScript = {
  version: 1;
  destination: string;
  reception: string;
  start: "person" | "next";
  personM: number;
  // the demo walk (docs/demo-mode.md, field test b16): the patient's side, the first check's turn and back,
  // seconds of driving after the first check until fellBack, the longest wait_close
  walkSide: "right" | "left";
  glanceDeg: number;
  fellBackAfterS: number;
  closeMaxS: number;
  parts: Partial<Record<DemoPart, DemoStep[]>>;
};

// the default script, exactly as in docs/demo-mode.md
export const DEFAULT_DEMO_SCRIPT: DemoScript = {
  version: 1,
  destination: "Dr. Green",
  reception: "Reception Point",
  start: "person",
  personM: 1.2,
  walkSide: "right",
  glanceDeg: 60,
  fellBackAfterS: 6,
  closeMaxS: 8,
  parts: {
    reception: [
      { who: "otto", text: "Hello, I'm Otto. How can I help you? Do you have an appointment?" },
      { who: "patient", text: "Yes, I have an appointment with Dr. Green." },
      { who: "action", do: "check_in" },
      { who: "otto", text: "Thank you. You're checked in for Dr. Green." },
      { who: "otto", text: "Your ticket number is {ticket}. I only use this number, never your name." },
      { who: "otto", text: "Shall I walk at a normal pace, or a little slower?" },
      { who: "patient", text: "A little slower, please." },
      { who: "action", do: "pace", value: "slow" },
      { who: "otto", text: "Of course. Please walk on my right. Let's go!" },
    ],
    firstCheck: [
      { who: "otto", text: "Is everything okay? Am I too fast?" },
      { who: "patient", text: "No, it's perfect, thank you." },
      { who: "otto", text: "Great, let's go on." },
    ],
    fellBack: [
      { who: "otto", text: "I'll wait for you. Take your time." },
      { who: "action", do: "wait_close" },
      { who: "patient", text: "Sorry, here I am." },
      { who: "otto", text: "No problem. We're almost there." },
    ],
    arrive: [
      { who: "otto", text: "Here we are, this is Dr. Green's room. Please take a seat. Dr. Green's team knows you're here." },
      { who: "patient", text: "Thank you, Otto!" },
      { who: "otto", text: "You're welcome. Get well soon!" },
    ],
    obstacle: [{ who: "otto", text: "Excuse me, may I pass?" }],
    detour: [{ who: "otto", text: "This way is blocked. Let's take another way." }],
  },
};

export function defaultScript(): DemoScript {
  return JSON.parse(JSON.stringify(DEFAULT_DEMO_SCRIPT)) as DemoScript;
}

export const PART_INFO: Record<DemoPart, { title: string; help: string }> = {
  reception: { title: "Reception", help: "Greeting, the check-in and the pace question. The walk starts after it." },
  firstCheck: { title: "First check-back", help: "Otto's first stop on the walk, about 10 s after leaving: he turns towards the patient's side, and back." },
  fellBack: { title: "Patient fell back", help: "A while after the first check-back (or on Next) Otto stops facing forward and waits until the patient is close again." },
  arrive: { title: "Arrival", help: "At the destination." },
  obstacle: { title: "Obstacle", help: "Someone or something stands in the way." },
  detour: { title: "Detour", help: "The way is blocked and Otto takes another one." },
};

export const MAX_STEPS = 30;
export const MAX_LINE = 300;
export const MAX_WAIT_S = 60;
export const PERSON_M = { min: 0.5, max: 3, default: 1.2 } as const;
// the demo walk: the first check's turn (0 = none), seconds of driving until fellBack, the longest wait_close
export const GLANCE_DEG = { min: 0, max: 90, default: 60 } as const;
export const FELL_BACK_AFTER_S = { min: 2, max: 30, default: 6 } as const;
export const CLOSE_MAX_S = { min: 3, max: 20, default: 8 } as const;

const PACE_LABEL: Record<DemoPace, string> = { normal: "normal", slow: "slow", very_slow: "very slow" };

// one line, no control characters, like the robot app reads texts
function clean(raw: unknown, max: number): string | null {
  if (typeof raw !== "string") return null;
  const t = raw.replace(/\s+/g, " ").replace(/[\u0000-\u001f\u007f]/g, "").trim();
  return t.length <= max ? t : null;
}

export type ScriptErrors = Record<string, string>;

// checks a script from the editor or the store. unknown fields are dropped, texts are tidied.
// errors per path: "destination", "parts.reception.3.text", ... (empty = fine)
export function checkScript(raw: unknown): { script: DemoScript; errors: ScriptErrors } {
  const errors: ScriptErrors = {};
  const src = raw && typeof raw === "object" && !Array.isArray(raw) ? (raw as Record<string, unknown>) : null;
  const script: DemoScript = {
    version: 1,
    destination: "",
    reception: "",
    start: "person",
    personM: PERSON_M.default,
    walkSide: "right",
    glanceDeg: GLANCE_DEG.default,
    fellBackAfterS: FELL_BACK_AFTER_S.default,
    closeMaxS: CLOSE_MAX_S.default,
    parts: {},
  };
  if (!src) {
    errors.script = "The script must be an object";
    return { script, errors };
  }
  const place = (key: "destination" | "reception", empty: string) => {
    const v = clean(src[key], 80);
    if (v === null) errors[key] = typeof src[key] === "string" ? "At most 80 characters" : empty;
    else if (!v) errors[key] = empty;
    else script[key] = v;
  };
  place("destination", "Pick where Otto walks the patient");
  place("reception", "Pick where Otto waits for the patient");
  if (src.start !== undefined) {
    if (src.start === "person" || src.start === "next") script.start = src.start;
    else errors.start = "Start must be person or next";
  }
  // a number in its range (missing = the default)
  const num = (key: "personM" | "glanceDeg" | "fellBackAfterS" | "closeMaxS", r: { min: number; max: number }, what: string, unit: string, whole = false) => {
    const raw = src[key];
    if (raw === undefined || raw === null) return;
    const n = typeof raw === "number" ? raw : typeof raw === "string" && raw.trim() ? Number(raw) : NaN;
    if (!Number.isFinite(n) || n < r.min || n > r.max) errors[key] = `${what} must be from ${r.min} to ${r.max} ${unit}`;
    else script[key] = whole ? Math.round(n) : Math.round(n * 10) / 10;
  };
  num("personM", PERSON_M, "The distance", "m");
  if (src.walkSide !== undefined && src.walkSide !== null) {
    if (src.walkSide === "right" || src.walkSide === "left") script.walkSide = src.walkSide;
    else errors.walkSide = "The walk side must be right or left";
  }
  num("glanceDeg", GLANCE_DEG, "The turn", "degrees", true);
  num("fellBackAfterS", FELL_BACK_AFTER_S, "The time until Otto waits", "s");
  num("closeMaxS", CLOSE_MAX_S, "The longest wait", "s");
  const parts = src.parts && typeof src.parts === "object" && !Array.isArray(src.parts) ? (src.parts as Record<string, unknown>) : null;
  if (src.parts !== undefined && !parts) errors.parts = "The parts must be an object";
  for (const part of DEMO_PARTS) {
    const list = parts?.[part];
    if (list === undefined || list === null) continue;
    if (!Array.isArray(list)) {
      errors[`parts.${part}`] = `${PART_INFO[part].title} must be a list of steps`;
      continue;
    }
    if (list.length > MAX_STEPS) errors[`parts.${part}`] = `${PART_INFO[part].title} can have at most ${MAX_STEPS} steps`;
    const steps: DemoStep[] = [];
    list.slice(0, MAX_STEPS).forEach((item, i) => {
      const at = `parts.${part}.${i}`;
      const o = item && typeof item === "object" ? (item as Record<string, unknown>) : {};
      if (o.who === "otto" || o.who === "patient") {
        const text = clean(o.text, MAX_LINE);
        if (text === null) errors[`${at}.text`] = typeof o.text === "string" ? `At most ${MAX_LINE} characters` : "Write the line";
        else if (!text) errors[`${at}.text`] = "Write the line";
        const step: { who: "otto" | "patient"; text: string; waitS?: number } = { who: o.who, text: text ?? "" };
        if (o.waitS !== undefined && o.waitS !== null && o.waitS !== "") {
          const n = typeof o.waitS === "number" ? o.waitS : Number(o.waitS);
          if (!Number.isFinite(n) || n < 0 || n > MAX_WAIT_S) errors[`${at}.waitS`] = `The wait must be from 0 to ${MAX_WAIT_S} s`;
          else step.waitS = Math.round(n * 10) / 10;
        }
        steps.push(step);
      } else if (o.who === "action") {
        if (o.do === "check_in" || o.do === "wait_close") steps.push({ who: "action", do: o.do });
        else if (o.do === "pace") {
          if ((DEMO_PACES as readonly unknown[]).includes(o.value)) steps.push({ who: "action", do: "pace", value: o.value as DemoPace });
          else {
            errors[`${at}.value`] = "Pick the pace";
            steps.push({ who: "action", do: "pace", value: "normal" });
          }
        } else {
          errors[`${at}.do`] = "Pick the action";
          steps.push({ who: "action", do: "check_in" });
        }
      } else {
        errors[`${at}.who`] = "Pick who it is: Otto, the patient or an action";
        steps.push({ who: "otto", text: "" });
      }
    });
    script.parts[part] = steps;
  }
  return { script, errors };
}

// ---------- timings, like the robot app

export function words(text: string) {
  return text.trim().split(/\s+/).filter(Boolean).length;
}

// how long the patient gets for a line: waitS, else 1.2 s + 0.4 s per word
export function patientWaitS(step: { text: string; waitS?: number }) {
  return step.waitS ?? Math.round((1.2 + 0.4 * words(step.text)) * 10) / 10;
}

// about the time otto's voice needs for a line
export function speakingS(text: string) {
  return Math.max(1, Math.round(words(text) * 0.4 * 10) / 10);
}

export function fillLine(text: string, values: { ticket?: string | null; place?: string | null }) {
  return text.replace(/\{ticket\}/g, values.ticket || "…").replace(/\{place\}/g, values.place || "…");
}

export function stepText(step: DemoStep, script?: Pick<DemoScript, "destination" | "closeMaxS">) {
  if (step.who === "otto" || step.who === "patient") return step.text;
  if (step.do === "check_in") return `Check-in: a real journey to ${script?.destination || "the destination"}, the ticket shows on Otto's screen`;
  if (step.do === "pace") return `Pace: ${PACE_LABEL[step.value]}`;
  return `Wait until the patient is close again (at most ${script?.closeMaxS ?? CLOSE_MAX_S.default} s, or Next)`;
}

// the next patient line from step `from` on in that part, else in the parts that may come after it
export function upcomingPatientLine(script: DemoScript, part: DemoPart | null, from: number, after: DemoPart[] = []) {
  const find = (p: DemoPart, i: number) => {
    const steps = script.parts[p] ?? [];
    for (let k = i; k < steps.length; k++) {
      const s = steps[k];
      if (s.who === "patient") return s.text;
    }
    return null;
  };
  if (part) {
    const here = find(part, from);
    if (here) return here;
  }
  for (const p of after) {
    const line = find(p, 0);
    if (line) return line;
  }
  return null;
}

// ---------- the places: matched by name first, then by spot (the robot does the same)

const norm = (v: string) => v.trim().replace(/\s+/g, " ").toLowerCase();

export function resolvePlace<P extends { id: string; name: string; spot: string }>(places: P[], value: string | null | undefined): P | undefined {
  if (!value) return undefined;
  const n = norm(value);
  return places.find((p) => p.id === value) ?? places.find((p) => norm(p.name) === n) ?? places.find((p) => norm(p.spot) === n);
}

// ---------- the heartbeat's demo object

export const DEMO_WAITING = ["reception", "person", "patient", "close", "walk", "hold", "back"] as const;
export type DemoWaiting = (typeof DEMO_WAITING)[number];

export type DemoState = {
  running: boolean;
  part: string | null;
  step: number | null;
  total: number | null;
  waitingFor: DemoWaiting | null;
  line: string | null;
  nextPatientLine: string | null;
  ticket: string | null;
  runs: number;
};

export const WAITING_TEXT: Record<DemoWaiting, string> = {
  reception: "Driving to the reception",
  person: "Waiting for a person in front of Otto",
  patient: "Waiting for the patient's line",
  close: "Waiting until the patient is close again",
  walk: "Walking with the patient",
  hold: "On hold",
  back: "Driving back to the reception",
};

// exactly the fields of docs/demo-mode.md, odd values become null. null = no demo object at all
export function cleanDemoState(raw: unknown): DemoState | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const str = (v: unknown, max: number) => (typeof v === "string" && v.trim() ? v.trim().replace(/\s+/g, " ").slice(0, max) : null);
  const int = (v: unknown) => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? Math.min(9999, Math.round(v)) : null);
  return {
    running: o.running === true,
    part: typeof o.part === "string" && /^[A-Za-z][A-Za-z_]{0,39}$/.test(o.part) ? o.part : null,
    step: int(o.step),
    total: int(o.total),
    waitingFor: (DEMO_WAITING as readonly unknown[]).includes(o.waitingFor) ? (o.waitingFor as DemoWaiting) : null,
    line: str(o.line, 400),
    nextPatientLine: str(o.nextPatientLine, 400),
    ticket: str(o.ticket, 16),
    runs: int(o.runs) ?? 0,
  };
}

export function partTitle(part: string | null) {
  return part && (DEMO_PARTS as readonly string[]).includes(part) ? PART_INFO[part as DemoPart].title : part;
}

// ---------- the command { type: "demo", op, script?, text? }

export const DEMO_OPS = ["start", "next", "hold", "resume", "restart", "stop", "say"] as const;
export type DemoOp = (typeof DEMO_OPS)[number];

export const DEMO_OP_LABEL: Record<DemoOp, string> = {
  start: "Start",
  next: "Next",
  hold: "Hold",
  resume: "Resume",
  restart: "Restart",
  stop: "Stop",
  say: "Say",
};

export const QUICK_LINES = ["One moment, please.", "Sorry, could you repeat that?"];
