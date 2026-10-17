"use client";

import { ArrowRight, Footprints } from "@phosphor-icons/react";
import Link from "next/link";
import { useEffect, useRef } from "react";
import type { JourneyWithQueue, Place } from "@/lib/types";
import { currentLeg, lineKey } from "@/lib/types";
import { STAGE_TONE, type Stage, type StageKey, placeText, stageOf } from "@/lib/ui";
import { JourneyActions, type Run } from "../components/journey-actions";
import { PaceNote, StageLine, TicketBadge } from "../components/journey-bits";

const ORDER: StageKey[] = ["failed", "staff", "room", "waiting", "walking", "pickup"];

type Group = { key: string; place?: Place; title: string; room?: string; rows: { j: JourneyWithQueue; stage: Stage }[] };

export function groupJourneys(journeys: JourneyWithQueue[], places: Place[], now: number): Group[] {
  const map = new Map<string, Group>();
  for (const j of journeys) {
    const key = lineKey(j);
    const place = places.find((p) => p.id === key);
    const leg = currentLeg(j);
    const g = map.get(key) ?? { key, place, title: place?.name ?? leg.toName, room: place?.room ?? leg.toRoom, rows: [] };
    g.rows.push({ j, stage: stageOf(j, now) });
    map.set(key, g);
  }
  const order = places.map((p) => p.id);
  const groups = [...map.values()].sort((a, b) => {
    const ai = order.indexOf(a.key);
    const bi = order.indexOf(b.key);
    return (ai < 0 ? 999 : ai) - (bi < 0 ? 999 : bi) || a.title.localeCompare(b.title);
  });
  for (const g of groups) {
    g.rows.sort(
      (a, b) =>
        ORDER.indexOf(a.stage.key) - ORDER.indexOf(b.stage.key) ||
        (a.j.queuePosition ?? 99) - (b.j.queuePosition ?? 99) ||
        a.j.createdAt.localeCompare(b.j.createdAt),
    );
  }
  return groups;
}

function counts(rows: Group["rows"]) {
  const n = (keys: StageKey[]) => rows.filter((r) => keys.includes(r.stage.key)).length;
  const parts = [
    [n(["pickup", "walking"]), "coming"],
    [n(["waiting", "room"]), "waiting"],
    [n(["staff"]), "with staff"],
    [n(["failed"]), n(["failed"]) === 1 ? "needs you" : "need you"],
  ] as const;
  return parts
    .filter(([c]) => c > 0)
    .map(([c, label]) => `${c} ${label}`)
    .join(", ");
}

export function JourneysPanel({
  journeys,
  places,
  now,
  run,
  busy,
}: {
  journeys: JourneyWithQueue[];
  places: Place[];
  now: number;
  run: Run;
  busy: string | null;
}) {
  const seen = useRef<Set<string> | null>(null);
  const fresh = new Set<string>();
  if (seen.current) for (const j of journeys) if (!seen.current.has(j.id)) fresh.add(j.id);
  useEffect(() => {
    seen.current = new Set(journeys.map((j) => j.id));
  }, [journeys]);

  const groups = groupJourneys(journeys, places, now);
  const needYou = journeys.filter((j) => currentLeg(j).status === "failed").length;

  return (
    <section aria-labelledby="moving-title" className="@container rounded-2xl border border-line bg-surface shadow-panel">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 pt-4 sm:px-6 sm:pt-6">
        <h2 id="moving-title" className="text-xl font-semibold tracking-tight">
          Patients on the move
        </h2>
        <p className="text-sm text-muted tabular">
          {journeys.length === 0 ? "None right now" : `${journeys.length} open`}
          {needYou > 0 && (
            <span className="font-semibold text-danger">
              , {needYou} {needYou === 1 ? "needs" : "need"} you
            </span>
          )}
        </p>
      </div>

      {groups.length === 0 ? (
        <div className="px-4 pt-6 pb-8 sm:px-6">
          <Footprints size={32} aria-hidden className="text-muted" />
          <p className="mt-3 max-w-prose text-[15px] text-muted">
            Nobody is on the way right now. When you send Nova, the walk shows up here live, grouped by room, with buttons for
            &quot;With me&quot;, the next room and the exit.
          </p>
        </div>
      ) : (
        <div className="mt-2 pb-2">
          {groups.map((g) => (
            <div key={g.key} className="border-t border-line first:border-t-0">
              <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 bg-subtle/60 px-4 py-2.5 sm:px-6">
                <h3 className="min-w-0 text-[15px] font-semibold">
                  {placeText({ name: g.title, room: g.room })}
                  <span className="ml-2 font-normal text-muted">{counts(g.rows)}</span>
                </h3>
                {g.place && (
                  <Link
                    href={`/room/${g.place.id}`}
                    className="flex h-9 items-center gap-1 rounded-lg px-2 text-sm font-semibold text-primary hover:bg-primary-soft"
                  >
                    Room view <ArrowRight size={16} aria-hidden />
                  </Link>
                )}
              </div>
              <ul className="divide-y divide-line">
                {g.rows.map(({ j, stage }) => (
                  <li
                    key={j.id}
                    className={`grid grid-cols-[64px_minmax(0,1fr)] gap-x-3 gap-y-3 px-4 py-4 sm:px-6 ${fresh.has(j.id) ? "arrive rounded-xl" : ""}`}
                  >
                    <TicketBadge ticket={j.ticket} stage={stage} />
                    <div className="min-w-0">
                      <p className="flex flex-wrap items-center gap-x-2 gap-y-1">
                        <span className={`text-[17px] font-semibold ${STAGE_TONE[stage.key].text}`}>{stage.title}</span>
                        <PaceNote journey={j} />
                      </p>
                      <p className="mt-0.5 text-[15px] text-muted tabular">{stage.detail}</p>
                      <div className="mt-2 max-w-60">
                        <StageLine stage={stage} />
                      </div>
                    </div>
                    <div className="col-span-2 @md:col-span-1 @md:col-start-2">
                      <JourneyActions journey={j} stage={stage} places={places} run={run} busy={busy} />
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
