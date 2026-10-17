"use client";

import { ArrowRight, DoorOpen, MapPin } from "@phosphor-icons/react";
import Link from "next/link";
import { lineKey } from "@/lib/types";
import { KIND_LABEL, PAGE } from "@/lib/ui";
import { useBoard } from "../components/use-board";

// doctors open this on their phone once, pick their room and keep the page
export function RoomPicker() {
  const { data } = useBoard(4000);
  const rooms = (data?.places ?? []).filter((p) => p.active && p.kind !== "reception" && p.kind !== "exit");

  return (
    <main className={`${PAGE} pt-6 pb-16`}>
      <div className="max-w-3xl">
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Rooms</h1>
        <p className="mt-1 max-w-prose text-[15px] text-muted">
          Pick your room. The room view shows who Otto brings to you, who waits, and has the buttons for the next room and the
          exit. Open it on your phone and keep it open.
        </p>

        {!data ? (
          <div className="mt-6 grid gap-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-20 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
            ))}
          </div>
        ) : rooms.length === 0 ? (
          <div className="mt-6 rounded-2xl border border-line bg-surface p-6 shadow-panel">
            <MapPin size={28} aria-hidden className="text-primary" />
            <h2 className="mt-3 text-lg font-semibold">No rooms yet</h2>
            <p className="mt-1 text-[15px] text-muted">Add your rooms on the Places page first.</p>
            <Link
              href="/places"
              className="mt-4 inline-flex h-12 items-center gap-2 rounded-xl bg-primary px-5 font-semibold text-on-primary hover:bg-primary-hover"
            >
              Set up places <ArrowRight size={18} aria-hidden />
            </Link>
          </div>
        ) : (
          <ul className="mt-6 grid gap-3 sm:grid-cols-2">
            {rooms.map((p) => {
              const n = data.journeys.filter((j) => lineKey(j) === p.id).length;
              return (
                <li key={p.id}>
                  <Link
                    href={`/room/${p.id}`}
                    className="press flex min-h-20 items-center gap-4 rounded-2xl border border-line bg-surface p-4 shadow-panel hover:border-line-strong"
                  >
                    <DoorOpen size={28} aria-hidden className="text-primary" />
                    <span className="min-w-0 flex-1">
                      <span className="block text-[17px] font-semibold break-words">{p.name}</span>
                      <span className="block text-sm text-muted">
                        {p.room ? `Room ${p.room}` : KIND_LABEL[p.kind]}
                        {n ? `, ${n} open` : ""}
                      </span>
                    </span>
                    <ArrowRight size={20} aria-hidden className="text-muted" />
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </main>
  );
}
