// otto's modes, switched at the top of the dashboard. only "normal" guides patients.
// settings.mode is normal / show / calm; demo (docs/demo-mode.md) is started and stopped with the demo command.
// shared by the server and the browser, no imports (tests load this file directly)

export const ROBOT_MODES = ["normal", "show", "calm"] as const;
export type RobotMode = (typeof ROBOT_MODES)[number];

// every mode otto can be in, as the header shows them
export const OTTO_MODES = ["normal", "show", "calm", "demo"] as const;
export type OttoMode = (typeof OTTO_MODES)[number];

export const MODE_INFO: Record<OttoMode, { label: string; help: string }> = {
  normal: { label: "Normal", help: "Receptionist: greets, talks and guides patients." },
  show: { label: "Show", help: "Face and animations only. Silent, does not react to patients. For presentations." },
  calm: { label: "Calm", help: "Stands still and quiet: no speech, no driving on its own, fine to be pushed aside." },
  demo: { label: "Demo", help: "The rehearsed demo for the judges: Otto only says the script's lines, the microphone stays off." },
};

// a mode for settings.mode (normal / show / calm)
export function isMode(value: unknown): value is RobotMode {
  return typeof value === "string" && (ROBOT_MODES as readonly string[]).includes(value);
}

// any mode otto can report or the header can pick, demo too
export function isOttoMode(value: unknown): value is OttoMode {
  return typeof value === "string" && (OTTO_MODES as readonly string[]).includes(value);
}

// the friendly note where a new walk can't start
export function blockedText(mode: OttoMode) {
  return `Otto is in ${MODE_INFO[mode].label} mode. Switch to Normal to guide patients`;
}
