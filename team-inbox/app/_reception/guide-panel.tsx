"use client";

import {
  ArrowRight,
  Check,
  DoorOpen,
  MagnifyingGlass,
  MapPin,
  NavigationArrow,
  Armchair,
  SignOut as ExitIcon,
  Wheelchair,
  X,
} from "@phosphor-icons/react";
import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";
import type { GuideCommand, JourneyWithQueue, Pace, Place } from "@/lib/types";
import { matchScore, placeWait } from "@/lib/types";
import { PACE_LABEL, placeText, post } from "@/lib/ui";
import { Button, Kbd, Segmented, Switch } from "../components/button";

const PACE_OPTIONS: { value: Pace; label: string; key: string }[] = [
  { value: "normal", label: "Normal", key: "1" },
  { value: "slow", label: "Slow", key: "2" },
  { value: "very_slow", label: "Very slow", key: "3" },
];
const PACE_KEYS: Record<string, Pace> = { "1": "normal", "2": "slow", "3": "very_slow" };

type Sent = { journey: JourneyWithQueue; command: GuideCommand; via?: Place; room: Place };

function texts(p: Place) {
  return [p.name, p.room, p.spot];
}

export function GuidePanel({ places, open, onSent }: { places: Place[]; open: JourneyWithQueue[]; onSent: () => Promise<void> }) {
  const active = places.filter((p) => p.active);
  const reception = active.find((p) => p.kind === "reception");
  const destinations = active.filter((p) => p.kind !== "reception");

  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<string | null>(null);
  const [pace, setPace] = useState<Pace>("normal");
  const [wheelchair, setWheelchair] = useState(false);
  const [straight, setStraight] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<Sent | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const again = useRef<HTMLButtonElement>(null);

  // best matches first while typing, the normal order otherwise
  const shown = useMemo(() => {
    if (!query.trim()) return destinations;
    return destinations
      .map((p, i) => ({ p, i, s: matchScore(query, texts(p)) }))
      .filter((x) => x.s > 0)
      .sort((a, b) => b.s - a.s || a.i - b.i)
      .map((x) => x.p);
  }, [query, destinations]);

  const selected = shown.find((p) => p.id === picked) ?? (query.trim() ? shown[0] : undefined);
  const waitingRoom = selected?.waitingPlaceId ? active.find((p) => p.id === selected.waitingPlaceId) : undefined;
  const goesVia = waitingRoom && !straight ? waitingRoom : undefined;

  useEffect(() => {
    // desk computers get the cursor in the quick bar right away, phones don't pop the keyboard
    if (window.matchMedia("(pointer: fine)").matches) input.current?.focus();
  }, []);

  useEffect(() => {
    if (sent) again.current?.focus();
  }, [sent]);

  const reset = () => {
    setQuery("");
    setPicked(null);
    setPace("normal");
    setWheelchair(false);
    setStraight(false);
    setError(null);
  };

  const send = async () => {
    if (!selected || busy) return;
    setBusy(true);
    setError(null);
    try {
      const body = goesVia
        ? { to: goesVia.id, forPlaceId: selected.id, pace, wheelchair }
        : { to: selected.id, pace, wheelchair };
      const res = await post<{ journey: JourneyWithQueue; command: GuideCommand }>("/api/journeys", body);
      setSent({ ...res, via: goesVia, room: selected });
      reset();
      await onSent();
    } catch (e) {
      setError(e instanceof Error ? e.message : "That did not work");
    }
    setBusy(false);
  };

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      send();
      return;
    }
    if (e.key === "Escape") {
      e.preventDefault();
      reset();
      return;
    }
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!shown.length) return;
      const i = selected ? shown.findIndex((p) => p.id === selected.id) : -1;
      const next = shown[(i + (e.key === "ArrowDown" ? 1 : -1) + shown.length) % shown.length];
      setPicked(next.id);
      return;
    }
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    // 1/2/3 and W are shortcuts only when they can't be part of a place name or room
    const key = e.key.toLowerCase();
    if (key in PACE_KEYS || key === "w") {
      const wouldMatch = destinations.some((p) => matchScore(query + e.key, texts(p)) > 0);
      if (!wouldMatch) {
        e.preventDefault();
        if (key === "w") setWheelchair((v) => !v);
        else setPace(PACE_KEYS[key]);
      }
    }
  };

  if (sent)
    return (
      <TicketShown
        sent={sent}
        againRef={again}
        onAgain={() => {
          setSent(null);
          input.current?.focus();
        }}
      />
    );

  return (
    <section aria-labelledby="guide-title" className="rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6">
      <div className="flex items-baseline justify-between gap-4">
        <h2 id="guide-title" className="text-xl font-semibold tracking-tight">
          Guide a patient
        </h2>
        {reception && <p className="truncate text-sm text-muted">Pickup at {reception.name}</p>}
      </div>

      {active.length === 0 ? (
        <SetupHint
          title="Add your places first"
          text="Nova needs to know where it picks patients up (the reception), where the exit is, and your rooms. You set them up once."
        />
      ) : !reception ? (
        <SetupHint
          title="Add the reception"
          text="Nova picks patients up at the reception. Add a place of kind Reception with its map spot."
        />
      ) : destinations.length === 0 ? (
        <SetupHint title="Add rooms" text="There is nowhere to walk to yet. Add your rooms, waiting rooms and the exit." />
      ) : (
        <>
          <label htmlFor="quick" className="mt-4 block text-sm font-semibold">
            Where to?
          </label>
          <div className="relative mt-2">
            <MagnifyingGlass
              size={22}
              aria-hidden
              className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 text-muted"
            />
            <input
              ref={input}
              id="quick"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setPicked(null);
              }}
              onKeyDown={onKey}
              placeholder="Type a name or room"
              autoComplete="off"
              spellCheck={false}
              aria-describedby="quick-help"
              aria-controls="destinations"
              className="h-14 w-full rounded-xl border border-line-strong bg-surface pr-12 pl-12 text-lg placeholder:text-muted focus:border-primary focus:outline-none focus-visible:outline-3 focus-visible:outline-offset-0 focus-visible:outline-primary/30"
            />
            {query && (
              <button
                type="button"
                onClick={() => {
                  reset();
                  input.current?.focus();
                }}
                className="absolute top-1/2 right-2 flex size-10 -translate-y-1/2 items-center justify-center rounded-lg text-muted hover:bg-subtle hover:text-ink"
                aria-label="Clear"
              >
                <X size={20} aria-hidden />
              </button>
            )}
          </div>
          <p id="quick-help" className="mt-2 hidden flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted sm:flex">
            <span className="flex items-center gap-1">
              <Kbd>Enter</Kbd> sends Nova
            </span>
            <span className="flex items-center gap-1">
              <Kbd>1</Kbd>
              <Kbd>2</Kbd>
              <Kbd>3</Kbd> pace
            </span>
            <span className="flex items-center gap-1">
              <Kbd>W</Kbd> wheelchair
            </span>
            <span className="flex items-center gap-1">
              <Kbd>Esc</Kbd> clear
            </span>
          </p>

          <div id="destinations" role="radiogroup" aria-label="Destination" className="mt-4 grid grid-cols-2 gap-2 sm:gap-3">
            {shown.map((p) => (
              <DestinationCard
                key={p.id}
                place={p}
                waiting={p.waitingPlaceId ? active.find((w) => w.id === p.waitingPlaceId) : undefined}
                wait={placeWait(open, p)}
                selected={selected?.id === p.id}
                onPick={() => setPicked(p.id)}
              />
            ))}
            {shown.length === 0 && (
              <p className="col-span-2 rounded-xl border border-dashed border-line-strong px-4 py-6 text-center text-[15px] text-muted">
                No place matches &quot;{query}&quot;. Check the spelling, or add it on Places.
              </p>
            )}
          </div>

          {waitingRoom && selected && (
            <div className="mt-5">
              <p className="mb-2 text-sm font-semibold">Walk to</p>
              <Segmented
                label="Walk to"
                value={straight ? "room" : "waiting"}
                onChange={(v) => setStraight(v === "room")}
                options={[
                  { value: "waiting", label: waitingRoom.name },
                  { value: "room", label: `Straight to ${selected.name}` },
                ]}
              />
              <p className="mt-2 text-sm text-muted">
                {straight
                  ? `Nova walks the patient right into ${placeText(selected)}.`
                  : `The patient waits in ${waitingRoom.name}. ${selected.name} gets a Teams card and calls them in.`}
              </p>
            </div>
          )}

          <div className="mt-5">
            <p className="mb-2 text-sm font-semibold">Pace</p>
            <Segmented label="Pace" value={pace} onChange={setPace} options={PACE_OPTIONS} size="lg" />
          </div>
          <div className="mt-3 rounded-xl border border-line px-4">
            <Switch
              id="wheelchair"
              checked={wheelchair}
              onChange={setWheelchair}
              icon={<Wheelchair size={26} aria-hidden />}
              label={
                <span className="flex items-center gap-2">
                  Wheelchair{" "}
                  <span className="hidden sm:inline">
                    <Kbd>W</Kbd>
                  </span>
                </span>
              }
              description="Nova keeps a slow pace"
            />
          </div>

          <Button kind="primary" size="xl" className="mt-5 w-full" disabled={!selected || busy} onClick={send}>
            <NavigationArrow size={24} weight="fill" aria-hidden />
            {busy ? "Sending Nova" : selected ? `Send Nova to ${goesVia ? goesVia.name : selected.name}` : "Send Nova"}
          </Button>
          <p className="mt-2 min-h-5 text-sm text-muted" role="status">
            {error ? (
              <span className="font-medium text-danger">{error}</span>
            ) : selected ? (
              `${PACE_LABEL[pace]} pace${wheelchair ? ", wheelchair" : ""}. Nova only gets a ticket and the room.`
            ) : (
              "Pick where the patient goes. Nova only gets a ticket and the room, never a name."
            )}
          </p>
        </>
      )}
    </section>
  );
}

function SetupHint({ title, text }: { title: string; text: string }) {
  return (
    <div className="mt-4 rounded-xl bg-subtle p-5">
      <MapPin size={28} aria-hidden className="text-primary" />
      <h3 className="mt-3 text-lg font-semibold">{title}</h3>
      <p className="mt-1 max-w-prose text-[15px] text-muted">{text}</p>
      <Link
        href="/places"
        className="press mt-4 inline-flex h-12 items-center gap-2 rounded-xl bg-primary px-5 font-semibold text-on-primary hover:bg-primary-hover"
      >
        Set up places <ArrowRight size={18} aria-hidden />
      </Link>
    </div>
  );
}

function KindIcon({ place }: { place: Place }) {
  const props = { size: 18, "aria-hidden": true } as const;
  if (place.kind === "exit") return <ExitIcon {...props} />;
  if (place.kind === "waiting") return <Armchair {...props} />;
  return <DoorOpen {...props} />;
}

function DestinationCard({
  place,
  waiting,
  wait,
  selected,
  onPick,
}: {
  place: Place;
  waiting?: Place;
  wait: { ahead: number; busy: boolean; minutes: number };
  selected: boolean;
  onPick: () => void;
}) {
  const room = place.kind === "room" || place.kind === "other";
  // main line = the wait, small line = why (people ahead, the waiting room)
  let line: { text: string; tone: string };
  if (place.kind === "exit") line = { text: "Way out", tone: "text-muted" };
  else if (place.kind === "waiting") line = { text: "Waiting room", tone: "text-muted" };
  else if (!wait.ahead && !wait.busy) line = { text: "No wait", tone: "text-ok" };
  else line = { text: `About ${wait.minutes} min`, tone: wait.minutes >= 20 ? "text-warn" : "text-ink" };
  const why = [
    place.kind === "waiting" && wait.ahead ? `${wait.ahead} on the way or seated` : "",
    room && wait.ahead ? `${wait.ahead} ahead` : room && wait.busy ? "Someone is inside" : "",
    room && waiting ? `via ${waiting.name}` : "",
  ].filter(Boolean);

  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onPick}
      className={`press relative flex min-h-24 flex-col items-start justify-between gap-2 rounded-xl border p-3 text-left sm:p-4 ${
        selected
          ? "border-primary bg-primary-soft ring-2 ring-primary"
          : "border-line bg-surface hover:border-line-strong hover:bg-subtle"
      }`}
    >
      <span className="flex w-full items-start gap-2 pr-6">
        <span className={`mt-0.5 ${selected ? "text-primary" : "text-muted"}`}>
          <KindIcon place={place} />
        </span>
        <span className="min-w-0">
          <span className="block text-[17px] leading-snug font-semibold break-words">{place.name}</span>
          {place.room && <span className="block text-sm text-muted">Room {place.room}</span>}
        </span>
      </span>
      <span className="block w-full text-sm">
        <span className={`block font-semibold ${line.tone}`}>{line.text}</span>
        {why.length > 0 && <span className="block text-muted">{why.join(", ")}</span>}
      </span>
      {selected && (
        <span className="absolute top-3 right-3 flex size-6 items-center justify-center rounded-full bg-primary text-on-primary">
          <Check size={14} weight="bold" aria-hidden />
        </span>
      )}
    </button>
  );
}

function TicketShown({
  sent,
  onAgain,
  againRef,
}: {
  sent: Sent;
  onAgain: () => void;
  againRef: React.RefObject<HTMLButtonElement | null>;
}) {
  const { journey, command, via, room } = sent;
  const pickup = command.from.name;
  const slow = journey.pace !== "normal" || journey.wheelchair;
  return (
    <section aria-labelledby="ticket-title" className="rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-6">
      <h2 id="ticket-title" className="text-xl font-semibold tracking-tight">
        Tell the patient
      </h2>
      <div className="pop mt-4 rounded-2xl bg-primary-soft px-4 py-6 text-center">
        <p className="text-sm font-medium text-primary-ink">Your ticket is</p>
        <p
          className="tabular text-[96px] leading-none font-bold tracking-tight text-primary-ink sm:text-[120px]"
          aria-live="polite"
        >
          {journey.ticket}
        </p>
        <p className="mt-3 text-[17px] font-semibold">{via ? `${via.name}, then ${placeText(room)}` : placeText(room)}</p>
        <p className="mt-1 text-sm text-muted">
          {PACE_LABEL[journey.pace]} pace{journey.wheelchair ? ", wheelchair" : ""}
        </p>
      </div>

      <h3 className="mt-6 text-sm font-semibold">What happens next</h3>
      <ol className="mt-3 grid gap-3">
        {[
          `Nova comes to ${pickup} and shows ${journey.ticket} on its screen.`,
          `The patient taps "That's me" on Nova, or says the ticket.`,
          `Nova walks with them${slow ? " slowly" : ""} to ${via ? via.name : room.name} and checks they keep up.`,
          via
            ? `${room.name} gets a Teams card and taps Call in when ready. Nova brings them over.`
            : `${room.name} sees ${journey.ticket} coming on the room view and in Teams.`,
        ].map((t, i) => (
          <li key={i} className="grid grid-cols-[32px_minmax(0,1fr)] items-start gap-3 text-[15px]">
            <span className="flex size-8 items-center justify-center rounded-full bg-subtle text-sm font-semibold text-muted">
              {i + 1}
            </span>
            <span className="pt-1">{t}</span>
          </li>
        ))}
      </ol>
      <Button ref={againRef} kind="primary" size="lg" className="mt-6 w-full" onClick={onAgain}>
        Guide the next patient
      </Button>
    </section>
  );
}
