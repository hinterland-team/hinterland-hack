// robot ids and the names people see. the real robot's api id stays "nova", its name is Otto.
// the simulation has its own id, so it can never take the real robot's jobs. no imports (tests load it directly)

export const REAL_ROBOT = "nova";
export const SIM_ROBOT = "otto-sim";

export function isSimRobot(id: string | null | undefined) {
  return id === SIM_ROBOT;
}

// "nova" -> "Otto", "otto-sim" -> "Virtual Otto", anything else capitalised
export function robotDisplayName(id: string) {
  if (id === REAL_ROBOT) return "Otto";
  if (id === SIM_ROBOT) return "Virtual Otto";
  return id.charAt(0).toUpperCase() + id.slice(1);
}
