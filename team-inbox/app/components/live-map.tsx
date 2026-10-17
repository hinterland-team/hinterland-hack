"use client";

import { MapTrifold, NavigationArrow, PersonSimpleWalk, Target } from "@phosphor-icons/react";
import { useState } from "react";
import type { FloorInfo, MapImageMeta, MapState, RobotStatus } from "@/lib/types";
import { KIND_LABEL, ROBOT, isSimRobot, post, robotLabel, seenAgo } from "@/lib/ui";
import { Button } from "./button";
import { type SpotInfo, distance, meters, spotLabel } from "./map-view";
import { useNow, usePoll } from "./use-poll";

// a frame older than this means otto stopped sending (app closed, offline)
export const MAP_STALE_SECONDS = 30;

export type LiveMap = {
  map: MapState | null;
  image: MapImageMeta | null;
  floors: FloorInfo[];
  loaded: boolean;
  failed: boolean;
};

type MapAnswer = { map: MapState | null; image?: MapImageMeta; floors?: FloorInfo[] };

// fast while otto sends often, slow when it is idle or away. 304 while nothing changed
export function useLiveMap(robot = ROBOT): LiveMap {
  const { data, failed } = usePoll<MapAnswer>(`/api/robot/map?robot=${encodeURIComponent(robot)}`, {
    every: (d) => (d?.map && Date.now() - Date.parse(d.map.updatedAt) < 20_000 ? 1000 : 4000),
  });
  return { map: data?.map ?? null, image: data?.image ?? null, floors: data?.floors ?? [], loaded: data !== null, failed };
}

// a clock of its own: the map does not re-render for "5 s ago"
export function useStale(map: MapState | null) {
  const now = useNow(5000);
  return Boolean(map && now !== null && (now - Date.parse(map.updatedAt)) / 1000 > MAP_STALE_SECONDS);
}

// the url of a floor plan png: the hash makes it cacheable
export function imageHref(robot: string, image: MapImageMeta) {
  return `/api/robot/map/image?robot=${encodeURIComponent(robot)}&map=${encodeURIComponent(image.mapName)}&v=${encodeURIComponent(image.hash)}`;
}

export function MapStats({ map }: { map: MapState }) {
  const now = useNow(1000);
  const stale = now !== null && (now - Date.parse(map.updatedAt)) / 1000 > MAP_STALE_SECONDS;
  const follower = map.follower ? distance(map.pose, map.follower) : null;
  const cell = "min-w-0";
  return (
    <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-[repeat(auto-fit,minmax(140px,1fr))]">
      <div className={cell}>
        <dt className="text-muted">Updated</dt>
        <dd className={`font-semibold tabular ${stale ? "text-warn" : ""}`}>{now === null ? " " : seenAgo(map.updatedAt, now)}</dd>
      </div>
      <div className={cell}>
        <dt className="flex items-center gap-1 text-muted">
          <Target size={14} aria-hidden /> Target
        </dt>
        <dd className="truncate font-semibold">
          {map.target ? (
            <>
              {map.target.name}
              {map.remainingMeters !== undefined && <span className="font-normal text-muted tabular">, {meters(map.remainingMeters)} left</span>}
            </>
          ) : (
            <span className="font-normal text-muted">None right now</span>
          )}
        </dd>
      </div>
      <div className={cell}>
        <dt className="flex items-center gap-1 text-muted">
          <PersonSimpleWalk size={14} aria-hidden /> Patient
        </dt>
        <dd className="truncate font-semibold tabular">
          {follower !== null && map.follower ? (
            <>
              {meters(follower)} away
              <span className="font-normal text-muted">
                {" "}
                ({map.follower.source === "lidar" ? "LiDAR" : map.follower.source === "camera" ? "camera" : "simulated"})
              </span>
            </>
          ) : (
            <span className="font-normal text-muted">Not tracked</span>
          )}
        </dd>
      </div>
      {map.mapName && (
        <div className={cell}>
          <dt className="text-muted">Map</dt>
          <dd className="truncate font-semibold">{map.mapName}</dd>
        </div>
      )}
    </dl>
  );
}

export function MapEmpty({ failed, loaded, robot = ROBOT }: { failed: boolean; loaded: boolean; robot?: string }) {
  return (
    <div className="flex min-h-56 flex-col items-start justify-center gap-3 rounded-xl border border-dashed border-line-strong bg-subtle/50 p-5">
      <MapTrifold size={32} aria-hidden className="text-muted" />
      <div>
        <p className="font-semibold">{failed ? "Can't load the map" : loaded ? "No map yet" : "Loading the map"}</p>
        <p className="mt-1 max-w-prose text-[15px] text-muted">
          {failed
            ? "The server did not answer. Retrying."
            : isSimRobot(robot)
              ? "Virtual Otto shows up here when the simulation is on."
              : "Otto sends its position, the spots on its map and what its LiDAR sees while its app runs: about once a second while it moves."}
        </p>
      </div>
    </div>
  );
}

// "what is otto busy with": the ticket on its screen during a walk
function busyWith(robot: RobotStatus | null) {
  const s = robot?.online ? robot.screen : null;
  if (!s?.ticket) return null;
  return ["pickup", "walking", "check_back", "waiting"].includes(s.view) ? s.ticket : null;
}

export function SpotPanel({
  spot,
  map,
  robot,
  onClose,
}: {
  spot: SpotInfo;
  map: MapState;
  robot: RobotStatus | null;
  onClose: () => void;
}) {
  const [state, setState] = useState<{ status: "idle" | "sending" | "sent" | "error"; message?: string }>({ status: "idle" });
  const name = spotLabel(spot);
  const away = distance(map.pose, spot);
  const ticket = busyWith(robot);
  const who = robotLabel(map.robot);

  const send = async () => {
    setState({ status: "sending" });
    try {
      await post("/api/robot/commands", { robot: map.robot, type: "go_to", spot: spot.name });
      setState({ status: "sent" });
    } catch (e) {
      setState({ status: "error", message: e instanceof Error ? e.message : "That did not work" });
    }
  };

  return (
    <div className="rise rounded-xl border border-primary bg-primary-soft/50 p-4" role="region" aria-label={`Selected: ${name}`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[17px] font-semibold break-words">
            {name}
            {spot.place?.room && <span className="ml-2 font-normal text-muted">Room {spot.place.room}</span>}
          </p>
          <p className="text-sm text-muted">
            {spot.place ? KIND_LABEL[spot.place.kind] : "A spot on Otto's map, not set up in Places"}
            {", "}
            <span className="tabular">{meters(away)} from {who} in a straight line</span>
          </p>
        </div>
        <Button kind="ghost" size="sm" onClick={onClose}>
          Close
        </Button>
      </div>
      {ticket && state.status !== "sent" && <p className="mt-3 text-sm font-medium text-warn">{who} is walking with ticket {ticket} right now.</p>}
      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Button kind="primary" size="lg" disabled={state.status === "sending" || state.status === "sent"} onClick={send}>
          <NavigationArrow size={20} weight="fill" aria-hidden />
          {state.status === "sending" ? "Sending" : state.status === "sent" ? "Sent" : `Send ${who} here`}
        </Button>
        <p className="min-w-0 flex-1 basis-40 text-sm" role="status">
          {state.status === "sent" ? (
            <span className="font-medium text-ok">
              {who} is on its way to {name}.
            </span>
          ) : state.status === "error" ? (
            <span className="font-medium text-danger">{state.message}</span>
          ) : (
            <span className="text-muted">
              {who} drives to the spot &quot;{spot.name}&quot; and stays there.
            </span>
          )}
        </p>
      </div>
    </div>
  );
}
