"use client";

import { ArrowRight } from "@phosphor-icons/react";
import Link from "next/link";
import { useMemo, useState } from "react";
import type { Place, RobotStatus } from "@/lib/types";
import { MapEmpty, MapStats, SpotPanel, useLiveMap } from "../components/live-map";
import { MapLegend, MapView, spotsOf } from "../components/map-view";

export function MapPanel({ places, robot }: { places: Place[]; robot: RobotStatus }) {
  const { map, loaded, failed, now, stale } = useLiveMap();
  const [picked, setPicked] = useState<string | null>(null);
  const spots = useMemo(() => (map ? spotsOf(map, places) : []), [map, places]);
  const spot = spots.find((s) => s.name === picked);

  return (
    <section aria-labelledby="map-title" className="rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6">
      <div className="flex items-baseline justify-between gap-4">
        <h2 id="map-title" className="text-xl font-semibold tracking-tight">
          Live map
        </h2>
        <Link
          href="/map"
          className="flex h-10 items-center gap-1 rounded-lg px-2 text-sm font-semibold text-primary hover:bg-primary-soft"
        >
          Full map <ArrowRight size={16} aria-hidden />
        </Link>
      </div>
      {!map ? (
        <div className="mt-3">
          <MapEmpty failed={failed} loaded={loaded} />
        </div>
      ) : (
        <>
          <div className="mt-3">
            <MapStats map={map} now={now} stale={stale} />
          </div>
          <div className="mt-3">
            <MapView
              map={map}
              spots={spots}
              picked={picked}
              onPick={setPicked}
              compact
              stale={stale}
              className="aspect-[4/3] lg:aspect-[16/11]"
              label={`Live map of ${map.mapName ?? "Otto's floor"}: Otto${map.target ? ` heading to ${map.target.name}` : ""}, ${spots.length} spots`}
            />
          </div>
          <div className="mt-3">
            <MapLegend follower={Boolean(map.follower)} target={Boolean(map.target)} />
          </div>
          {spot ? (
            <div className="mt-4">
              <SpotPanel key={spot.name} spot={spot} map={map} robot={robot} onClose={() => setPicked(null)} />
            </div>
          ) : (
            <p className="mt-3 text-sm text-muted">Tap a place on the map to send Otto there.</p>
          )}
        </>
      )}
    </section>
  );
}
