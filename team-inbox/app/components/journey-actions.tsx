"use client";

import { ArrowBendUpRight, ArrowsClockwise, CheckCircle, HandWaving, SignOut as ExitIcon, X } from "@phosphor-icons/react";
import { useState } from "react";
import type { JourneyWithQueue, Place } from "@/lib/types";
import { currentLeg } from "@/lib/types";
import { type Stage, placeText, post } from "@/lib/ui";
import { Button } from "./button";

export type Run = (key: string, fn: () => Promise<unknown>) => Promise<void>;

// the buttons for one journey, the same on the reception board and the room view
export function JourneyActions({
  journey,
  stage,
  places,
  run,
  busy,
  big = false,
}: {
  journey: JourneyWithQueue;
  stage: Stage;
  places: Place[];
  run: Run;
  busy: string | null;
  big?: boolean;
}) {
  const [mode, setMode] = useState<"idle" | "next" | "cancel">("idle");
  const id = journey.id;
  const leg = currentLeg(journey);
  const active = places.filter((p) => p.active);
  const exit = active.find((p) => p.kind === "exit");
  const forPlace = journey.forPlaceId ? active.find((p) => p.id === journey.forPlaceId) : undefined;
  const size = big ? "lg" : "md";
  const wide = big ? "w-full sm:w-auto sm:flex-1" : "grow basis-36";
  const is = (key: string) => busy === `${id}:${key}`;
  const any = busy?.startsWith(`${id}:`) ?? false;
  const act = (key: string, path: string, body?: unknown, method = "POST") =>
    run(`${id}:${key}`, () => post(`/api/journeys/${id}${path}`, body, method));

  if (mode === "cancel") {
    return (
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label={`Cancel ${journey.ticket}?`}>
        <span className="w-full text-[15px] font-medium sm:w-auto">Cancel {journey.ticket}? Otto stops this guide.</span>
        <Button kind="danger" size={size} className={wide} disabled={any} onClick={() => act("cancel", "/cancel")}>
          Yes, cancel
        </Button>
        <Button size={size} className={wide} onClick={() => setMode("idle")}>
          Keep
        </Button>
      </div>
    );
  }

  if (mode === "next") {
    const choices = active.filter((p) => p.kind !== "reception" && p.id !== leg.toPlaceId);
    return (
      <div className="w-full" role="group" aria-label={`Next place for ${journey.ticket}`}>
        <p className="mb-2 text-[15px] font-semibold">Where should Otto take {journey.ticket} next?</p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {choices.map((p) => (
            <Button
              key={p.id}
              size={size}
              className="h-auto min-h-11 justify-start py-2 text-left whitespace-normal"
              disabled={any}
              onClick={() => act(`next:${p.id}`, "/next", { to: p.id }).then(() => setMode("idle"))}
            >
              <span className="min-w-0">
                <span className="block leading-snug">{p.name}</span>
                {p.room && <span className="block text-xs font-normal text-muted">Room {p.room}</span>}
              </span>
            </Button>
          ))}
        </div>
        <Button kind="ghost" size="sm" className="mt-2" onClick={() => setMode("idle")}>
          Close
        </Button>
      </div>
    );
  }

  const withMe = (
    <Button
      kind={stage.key === "room" ? "primary" : "secondary"}
      size={size}
      className={wide}
      disabled={any}
      onClick={() => act("with", "", { status: "with_staff" }, "PATCH")}
    >
      <HandWaving size={20} aria-hidden />
      {is("with") ? "Saving" : "With me"}
    </Button>
  );
  const toExit = exit ? (
    <Button
      kind={stage.key === "staff" ? "primary" : "secondary"}
      size={size}
      className={wide}
      disabled={any}
      onClick={() => act("exit", "/next", { to: exit.id })}
    >
      <ExitIcon size={20} aria-hidden />
      {is("exit") ? "Sending Otto" : "Done → exit"}
    </Button>
  ) : null;
  const finished = (
    <Button
      kind={!exit && stage.key === "staff" ? "primary" : "secondary"}
      size={size}
      className={wide}
      disabled={any}
      onClick={() => act("done", "/done")}
    >
      <CheckCircle size={20} aria-hidden />
      {is("done") ? "Saving" : "Finished"}
    </Button>
  );
  const nextRoom = (
    <Button size={size} className={wide} disabled={any} onClick={() => setMode("next")}>
      <ArrowBendUpRight size={20} aria-hidden />
      Next room…
    </Button>
  );
  const cancel = (
    <Button kind="quiet" size={size} className={wide} disabled={any} onClick={() => setMode("cancel")}>
      <X size={18} aria-hidden />
      Cancel
    </Button>
  );

  let buttons: React.ReactNode;
  switch (stage.key) {
    case "failed":
      buttons = (
        <>
          <Button kind="primary" size={size} className={wide} disabled={any} onClick={() => act("retry", "/retry")}>
            <ArrowsClockwise size={20} aria-hidden />
            {is("retry") ? "Sending Otto" : "Send Otto again"}
          </Button>
          {cancel}
        </>
      );
      break;
    case "pickup":
      buttons = cancel;
      break;
    case "walking":
      buttons = (
        <>
          {withMe}
          {cancel}
        </>
      );
      break;
    case "waiting":
      buttons = (
        <>
          {forPlace && (
            <Button
              kind="primary"
              size={size}
              className={wide}
              disabled={any}
              onClick={() => act("call", "/next", { to: forPlace.id })}
            >
              <ArrowBendUpRight size={20} aria-hidden />
              {is("call") ? "Sending Otto" : `Call in to ${forPlace.name}`}
            </Button>
          )}
          {nextRoom}
          {cancel}
        </>
      );
      break;
    case "room":
      buttons = (
        <>
          {withMe}
          {toExit}
          {nextRoom}
          {cancel}
        </>
      );
      break;
    case "staff":
      buttons = (
        <>
          {toExit}
          {nextRoom}
          {finished}
        </>
      );
      break;
  }
  return (
    <div
      className="flex flex-wrap gap-2"
      aria-label={`Actions for ${journey.ticket} at ${placeText({ name: leg.toName, room: leg.toRoom })}`}
    >
      {buttons}
    </div>
  );
}
