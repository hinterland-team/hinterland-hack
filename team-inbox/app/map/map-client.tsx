"use client";

import { Info, MapPin, Stack } from "@phosphor-icons/react";
import { useMemo, useState } from "react";
import type { FloorInfo, MapImageMeta, MapSpot, MapState } from "@/lib/types";
import { KIND_LABEL, PAGE, ROBOT, SIM_ROBOT, isSimRobot } from "@/lib/ui";
import { FloorSwitch } from "../_reception/live-panel";
import { SimBadge } from "../components/sim-badge";
import { Segmented } from "../components/button";
import { MapEmpty, MapStats, SpotPanel, imageHref, useLiveMap, useStale } from "../components/live-map";
import { MapLegend, MapView, distance, meters, spotLabel, spotsOf } from "../components/map-view";
import { useAction, useBoard } from "../components/use-board";
import { usePoll } from "../components/use-poll";

type FloorAnswer = { name: string; spots: MapSpot[]; image?: MapImageMeta };

export function MapClient() {
  const [robot, setRobot] = useState(ROBOT);
  const { data, reload } = useBoard(3000, robot);
  const live = useLiveMap(robot);
  const { map, image, floors, loaded, failed } = live;
  const stale = useStale(map);
  const { run, busy, error } = useAction(reload);
  const [picked, setPicked] = useState<string | null>(null);
  const [viewFloor, setViewFloor] = useState<string | null>(null);
  const places = useMemo(() => data?.places ?? [], [data]);
  const simOpen = Boolean(data?.journeys.some((j) => j.robot === SIM_ROBOT));
  const current = floors.find((f) => f.current)?.name ?? map?.mapName ?? null;
  // another floor than the one otto is on: its plan and the spots otto reported there, without otto
  const other = viewFloor && viewFloor !== current ? viewFloor : null;
  const { data: floorData } = usePoll<FloorAnswer>(other ? `/api/robot/floor?robot=${encodeURIComponent(robot)}&map=${encodeURIComponent(other)}` : null, {
    every: 10_000,
  });
  const shown: MapState | null = useMemo(() => {
    if (!other) return map;
    const spots = floorData?.spots ?? [];
    const c = spots.length ? { x: spots.reduce((a, s) => a + s.x, 0) / spots.length, y: spots.reduce((a, s) => a + s.y, 0) / spots.length } : { x: 0, y: 0 };
    return { robot, mapName: other, pose: { ...c, theta: 0 }, places: spots, scan: [], trail: [], updatedAt: new Date(0).toISOString() };
  }, [other, map, floorData, robot]);
  const shownImage = other ? (floorData?.image ?? null) : image;
  const plan = useMemo(() => (shownImage ? { ...shownImage, href: imageHref(robot, shownImage) } : null), [shownImage, robot]);
  const spots = useMemo(() => (shown ? spotsOf(shown, places) : []), [shown, places]);
  const spot = spots.find((s) => s.name === picked);
  const sorted = useMemo(
    () => (shown ? [...spots].sort((a, b) => (other ? a.name.localeCompare(b.name) : distance(shown.pose, a) - distance(shown.pose, b))) : []),
    [spots, shown, other],
  );
  const walking = Boolean(data?.robot.screen && ["pickup", "walking", "check_back", "waiting"].includes(data.robot.screen.view) && data.robot.online);

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
        <div>
          <h1 className="flex flex-wrap items-center gap-2 text-2xl font-semibold tracking-tight sm:text-3xl">
            Map {isSimRobot(robot) && <SimBadge />}
          </h1>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            Where Otto is, where it goes and what its LiDAR sees, live from the robot. Select a place to send Otto there.
          </p>
        </div>
        {(simOpen || isSimRobot(robot)) && (
          <div className="w-full max-w-xs">
            <Segmented
              label="Which Otto"
              value={robot}
              onChange={(v) => {
                setRobot(v);
                setViewFloor(null);
                setPicked(null);
              }}
              options={[
                { value: ROBOT, label: "Otto" },
                { value: SIM_ROBOT, label: "Virtual Otto" },
              ]}
            />
          </div>
        )}
      </div>

      {floors.length > 1 && (
        <FloorTabs floors={floors} value={other ?? current} onChange={(f) => (setViewFloor(f), setPicked(null))} />
      )}

      {!shown ? (
        <MapEmpty failed={failed} loaded={loaded} robot={robot} />
      ) : (
        <>
          {other ? (
            <p className="flex items-start gap-2 rounded-xl bg-subtle px-4 py-3 text-[15px] text-muted">
              <Stack size={20} aria-hidden className="mt-0.5 shrink-0" />
              Otto is on {current ?? "another floor"}. This is {other}: the spots Otto reported there{plan ? " on its floor plan" : ""}.
            </p>
          ) : (
            <div className="rounded-2xl border border-line bg-surface px-4 py-3 shadow-panel sm:px-6">
              <MapStats map={shown} />
            </div>
          )}
          {!other && stale && (
            <p className="mt-3 rounded-xl bg-warn-soft px-4 py-3 text-[15px] font-medium text-warn" role="status">
              No new position for a while. Otto may be offline or its app is closed. This is its last known spot.
            </p>
          )}
          {!plan && (
            <p className="mt-3 flex items-start gap-2 text-sm text-muted">
              <Info size={18} aria-hidden className="mt-px shrink-0" />
              No floor plan from Otto for {shown.mapName ?? "this map"} yet, so the places show as dots. Otto sends the floor plan when its app starts.
            </p>
          )}
          <div className="mt-4 grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_360px] xl:gap-5">
            <div className="min-w-0">
              <MapView
                map={shown}
                plan={plan}
                robotVisible={!other}
                spots={spots}
                picked={picked}
                onPick={setPicked}
                stale={!other && stale}
                className="aspect-[4/3] sm:aspect-auto sm:h-[min(68vh,760px)] sm:min-h-[340px]"
                label={`${other ? "Floor" : "Live map"} ${shown.mapName ?? "Otto's floor"}${other ? "" : `: Otto${shown.target ? ` heading to ${shown.target.name}` : ""}`}, ${spots.length} spots`}
                toolbarStart={
                  other ? undefined : (
                    <div className="hidden sm:block">
                      <MapLegend follower={Boolean(shown.follower)} target={Boolean(shown.target)} />
                    </div>
                  )
                }
              />
              <div className="mt-3 flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
                {!other && (
                  <div className="sm:hidden">
                    <MapLegend follower={Boolean(shown.follower)} target={Boolean(shown.target)} />
                  </div>
                )}
                <p className="text-sm text-muted">Drag to move, scroll or pinch to zoom. Fit shows the whole floor.</p>
              </div>
            </div>

            <div className="grid gap-4">
              {other && !isSimRobot(robot) ? (
                <section aria-label={`Switch Otto to ${other}`}>
                  <FloorSwitch key={other} floors={floors} places={places} walking={walking} run={run} busy={busy} initial={other} />
                  {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}
                </section>
              ) : spot ? (
                <SpotPanel key={spot.name} spot={spot} map={shown} robot={data?.robot ?? null} onClose={() => setPicked(null)} />
              ) : (
                <p className="rounded-xl border border-dashed border-line-strong px-4 py-4 text-[15px] text-muted">
                  Select a place on the map or in the list to send Otto there.
                </p>
              )}
              <section aria-labelledby="spots-title" className="rounded-2xl border border-line bg-surface shadow-panel">
                <h2 id="spots-title" className="flex items-baseline justify-between gap-3 px-4 pt-4 pb-2 sm:px-5">
                  <span className="text-lg font-semibold">Places on {other ? other : "the map"}</span>
                  <span className="text-sm font-normal text-muted tabular">{spots.length}</span>
                </h2>
                {sorted.length === 0 ? (
                  <p className="px-4 pb-4 text-[15px] text-muted sm:px-5">Otto has not sent the spots of this map yet.</p>
                ) : (
                  <ul className="pb-2">
                    {sorted.map((s) => {
                      const on = s.name === picked;
                      return (
                        <li key={s.name}>
                          <button
                            type="button"
                            onClick={() => setPicked(on ? null : s.name)}
                            aria-pressed={on}
                            disabled={Boolean(other)}
                            className={`press flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left disabled:cursor-default sm:px-5 ${on ? "bg-primary-soft" : other ? "" : "hover:bg-subtle"}`}
                          >
                            <MapPin size={20} weight={on ? "fill" : "regular"} aria-hidden className={s.place ? "text-primary" : "text-muted"} />
                            <span className="min-w-0 flex-1">
                              <span className="block font-semibold break-words">{spotLabel(s)}</span>
                              <span className="block text-sm text-muted">
                                {s.place
                                  ? s.place.room
                                    ? s.place.kind === "room"
                                      ? `Room ${s.place.room}`
                                      : `${KIND_LABEL[s.place.kind]}, Room ${s.place.room}`
                                    : KIND_LABEL[s.place.kind]
                                  : "Map spot only, not in Places"}
                              </span>
                            </span>
                            {!other && <span className="text-sm text-muted tabular">{meters(distance(shown.pose, s))}</span>}
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </section>
            </div>
          </div>
        </>
      )}
    </main>
  );
}

function FloorTabs({ floors, value, onChange }: { floors: FloorInfo[]; value: string | null; onChange: (f: string) => void }) {
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2" role="tablist" aria-label="Floors">
      <span className="mr-1 flex items-center gap-1.5 text-sm font-semibold text-muted">
        <Stack size={18} aria-hidden /> Floor
      </span>
      {floors.map((f) => {
        const on = f.name === value;
        return (
          <button
            key={f.name}
            type="button"
            role="tab"
            aria-selected={on}
            onClick={() => onChange(f.name)}
            className={`press inline-flex h-10 items-center gap-2 rounded-xl border px-3 text-sm font-semibold ${
              on ? "border-primary bg-primary-soft text-primary-ink" : "border-line bg-surface text-muted hover:text-ink"
            }`}
          >
            {f.name}
            {f.current && <span className="rounded-full bg-ok-soft px-2 py-0.5 text-xs text-ok">Otto is here</span>}
          </button>
        );
      })}
    </div>
  );
}
