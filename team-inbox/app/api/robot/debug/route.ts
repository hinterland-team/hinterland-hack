import { robotName } from "@/lib/common";
import { configView, robotConfig } from "@/lib/config";
import { floorsOf, mapImageMeta } from "@/lib/floors";
import { jsonEtag, requireStaff, route } from "@/lib/http";
import { errorCount, latestPerf } from "@/lib/logs";
import { getRobotStatus, guidingNow, listCommands } from "@/lib/robot";
import { getStore } from "@/lib/store";

export const dynamic = "force-dynamic";

// Settings -> Debug (robot-setup.md section 4): what otto runs, where it is, how fast its screen is, errors, the raw config
export const GET = route(async (req) => {
  await requireStaff(req);
  const robot = robotName(new URL(req.url).searchParams.get("robot"));
  const store = getStore();
  const [status, config, raw, perf, errors, places, map, commands, walk] = await Promise.all([
    getRobotStatus(robot),
    configView(robot),
    robotConfig(robot),
    latestPerf(robot),
    errorCount(robot),
    store.listPlaces(),
    store.getMap(robot),
    listCommands(robot, 8),
    guidingNow(robot),
  ]);
  const floors = await floorsOf(robot, places);
  const mapName = status.mapName ?? map?.mapName ?? null;
  const image = mapName ? await mapImageMeta(robot, mapName) : null;
  const data = {
    robot,
    status,
    config: { version: config.version, applied: config.applied, updatedAt: config.updatedAt },
    rawConfig: raw,
    perf,
    errors,
    map: { name: mapName, updatedAt: map?.updatedAt ?? null, image, floors },
    commands,
    walking: walk ? walk.ticket : null,
  };
  return jsonEtag(req, data);
});
