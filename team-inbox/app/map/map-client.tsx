"use client";

import { MapPin } from "@phosphor-icons/react";
import { useMemo, useState } from "react";
import { PAGE, KIND_LABEL } from "@/lib/ui";
import { MapEmpty, MapStats, SpotPanel, useLiveMap } from "../components/live-map";
import { MapLegend, MapView, distance, meters, spotLabel, spotsOf } from "../components/map-view";
import { useBoard } from "../components/use-board";

export function MapClient() {
  const { data } = useBoard(3000);
  const { map, loaded, failed, now, stale } = useLiveMap();
  const [picked, setPicked] = useState<string | null>(null);
  const places = useMemo(() => data?.places ?? [], [data]);
  const spots = useMemo(() => (map ? spotsOf(map, places) : []), [map, places]);
  const spot = spots.find((s) => s.name === picked);
  const sorted = useMemo(
    () => (map ? [...spots].sort((a, b) => distance(map.pose, a) - distance(map.pose, b)) : []),
    [spots, map],
  );

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="pt-6 pb-4">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Map</h1>
        <p className="mt-1 max-w-prose text-[15px] text-muted">
          Where Otto is, where it goes and what its LiDAR sees, live from the robot. Select a place to send Otto there.
        </p>
      </div>

      {!map ? (
        <MapEmpty failed={failed} loaded={loaded} />
      ) : (
        <>
          <div className="rounded-2xl border border-line bg-surface px-4 py-3 shadow-panel sm:px-6">
            <MapStats map={map} now={now} stale={stale} />
          </div>
          {stale && (
            <p className="mt-3 rounded-xl bg-warn-soft px-4 py-3 text-[15px] font-medium text-warn" role="status">
              No new position for a while. Otto may be offline or the Nova Clinic app is closed. This is its last known spot.
            </p>
          )}
          <div className="mt-4 grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_360px] xl:gap-5">
            <div className="min-w-0">
              <MapView
                map={map}
                spots={spots}
                picked={picked}
                onPick={setPicked}
                stale={stale}
                className="aspect-[4/3] sm:aspect-auto sm:h-[min(68vh,760px)] sm:min-h-[340px]"
                label={`Live map of ${map.mapName ?? "Otto's floor"}: Otto${map.target ? ` heading to ${map.target.name}` : ""}, ${spots.length} spots`}
                toolbarStart={
                  <div className="hidden sm:block">
                    <MapLegend follower={Boolean(map.follower)} target={Boolean(map.target)} />
                  </div>
                }
              />
              <div className="mt-3 flex flex-wrap items-center justify-between gap-x-6 gap-y-2">
                <div className="sm:hidden">
                  <MapLegend follower={Boolean(map.follower)} target={Boolean(map.target)} />
                </div>
                <p className="text-sm text-muted">Drag to move, scroll or pinch to zoom. Fit shows the whole floor.</p>
              </div>
            </div>

            <div className="grid gap-4">
              {spot ? (
                <SpotPanel key={spot.name} spot={spot} map={map} robot={data?.robot ?? null} onClose={() => setPicked(null)} />
              ) : (
                <p className="rounded-xl border border-dashed border-line-strong px-4 py-4 text-[15px] text-muted">
                  Select a place on the map or in the list to send Otto there.
                </p>
              )}
              <section aria-labelledby="spots-title" className="rounded-2xl border border-line bg-surface shadow-panel">
                <h2 id="spots-title" className="flex items-baseline justify-between gap-3 px-4 pt-4 pb-2 sm:px-5">
                  <span className="text-lg font-semibold">Places on the map</span>
                  <span className="text-sm font-normal text-muted tabular">{spots.length}</span>
                </h2>
                {sorted.length === 0 ? (
                  <p className="px-4 pb-4 text-[15px] text-muted sm:px-5">Otto has not sent the spots of its map yet.</p>
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
                            className={`press flex min-h-14 w-full items-center gap-3 px-4 py-2 text-left sm:px-5 ${on ? "bg-primary-soft" : "hover:bg-subtle"}`}
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
                            <span className="text-sm text-muted tabular">{meters(distance(map.pose, s))}</span>
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
