// what /demo shows (docs/demo-mode.md, Dashboard): otto's demo state from its heartbeat, the script, the mode and
// otto's answers to the demo commands. for the real otto and for virtual otto
import { modeView } from "./config";
import { readScript, withoutScript } from "./demo";
import { isSimRobot, robotDisplayName } from "./robots";
import { simIsOn } from "./sim";
import { getStore } from "./store";
import { robotStatus } from "./types";

export async function demoView(robot: string) {
  const store = getStore();
  const [{ lastSeenAt, heartbeat }, info, commands] = await Promise.all([store.getRobot(robot), readScript(), store.listCommands(robot, 40)]);
  const status = robotStatus(robot, lastSeenAt, heartbeat, Date.now());
  const sim = isSimRobot(robot);
  const mode = sim ? null : await modeView(robot);
  return {
    robot,
    name: robotDisplayName(robot),
    online: status.online,
    seenAt: status.lastSeenAt ?? null,
    // the mode that counts (the header's), for the real otto
    mode: mode ? { mode: mode.mode, switching: mode.switching, onRobot: mode.onRobot } : null,
    demo: status.demo ?? null,
    script: info.script,
    isDefault: info.isDefault,
    scriptUpdatedAt: info.updatedAt,
    // otto's answers: the demo commands and say lines, newest first
    commands: commands.filter((c) => c.type === "demo" || c.type === "say").slice(0, 8).map(withoutScript),
    sim: sim ? { on: simIsOn() } : null,
  };
}

export type DemoView = Awaited<ReturnType<typeof demoView>>;
