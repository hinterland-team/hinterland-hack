"use client";

import {
  Armchair,
  ArrowRight,
  CheckCircle,
  Circle,
  DoorOpen,
  MapPin,
  Minus,
  PencilSimple,
  Plus,
  SignOut as ExitIcon,
  Trash,
  UsersThree,
} from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import type { Place, PlaceKind } from "@/lib/types";
import { ALWAYS_PUBLIC, DEFAULT_MINUTES, MAX_MINUTES, MIN_MINUTES, PLACE_KINDS, ROOM_KINDS } from "@/lib/types";
import { KIND_LABEL, LoginNeeded, PAGE, api, post } from "@/lib/ui";
import { Button, Switch } from "../components/button";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";

const KIND_HELP: Record<PlaceKind, string> = {
  room: "A doctor's or treatment room. Staff send patients here.",
  waiting: "Patients sit here until the room calls them in.",
  reception: "Where Otto picks patients up and waits. Only one.",
  exit: "Where Otto says goodbye. Only one.",
  other: "Lab, X-ray, pharmacy, toilets, anything else.",
};

const GROUPS: { kind: PlaceKind; title: string }[] = [
  { kind: "reception", title: "Reception" },
  { kind: "waiting", title: "Waiting rooms" },
  { kind: "room", title: "Rooms" },
  { kind: "other", title: "Other places" },
  { kind: "exit", title: "Exit" },
];

function KindIcon({ kind, size = 20 }: { kind: PlaceKind; size?: number }) {
  const props = { size, "aria-hidden": true } as const;
  if (kind === "exit") return <ExitIcon {...props} />;
  if (kind === "waiting") return <Armchair {...props} />;
  if (kind === "reception") return <UsersThree {...props} />;
  if (kind === "other") return <MapPin {...props} />;
  return <DoorOpen {...props} />;
}

export function PlacesClient({ practiceChannel, emailOn }: { practiceChannel: boolean; emailOn: boolean }) {
  const [places, setPlaces] = useState<Place[] | null>(null);
  const [adding, setAdding] = useState<PlaceKind | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setPlaces(await api<Place[]>("/api/places"));
      setError(null);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not load the places");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const has = (kind: PlaceKind) => places?.some((p) => p.kind === kind && p.active) ?? false;
  const rooms = places?.filter((p) => p.kind === "room").length ?? 0;
  const anyRoom = places?.some((p) => p.kind === "room" || p.kind === "other") ?? false;
  const steps = [
    { done: has("reception"), label: "Reception", note: "where Otto picks patients up", kind: "reception" as const },
    { done: has("exit"), label: "Exit", note: "where Otto says goodbye", kind: "exit" as const },
    {
      done: anyRoom,
      label: rooms > 0 ? `${rooms} ${rooms === 1 ? "room" : "rooms"}` : "Rooms",
      note: "where staff send patients",
      kind: "room" as const,
    },
  ];

  return (
    <main className={`${PAGE} pb-16`}>
      <div>
        <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Places</h1>
            <p className="mt-1 max-w-prose text-[15px] text-muted">
              Where Otto can walk. The map spot must match the spot name on Otto&apos;s map exactly.
            </p>
          </div>
          {!adding && (
            <Button kind="primary" size="lg" onClick={() => setAdding("room")}>
              <Plus size={20} weight="bold" aria-hidden /> Add a place
            </Button>
          )}
        </div>

        {error && (
          <p className="mb-4 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger">{error}</p>
        )}

        {places && (
          <section
            aria-label="Setup"
            className="grid max-w-4xl gap-px overflow-hidden rounded-2xl border border-line bg-line sm:grid-cols-3"
          >
            {steps.map((s) => (
              <div key={s.kind} className="flex items-center gap-3 bg-surface px-4 py-3">
                {s.done ? (
                  <CheckCircle size={26} weight="fill" className="shrink-0 text-ok" aria-label="done" />
                ) : (
                  <Circle size={26} className="shrink-0 text-line-strong" aria-label="missing" />
                )}
                <div className="min-w-0 flex-1">
                  <p className="font-semibold">{s.label}</p>
                  <p className="text-sm text-muted">{s.note}</p>
                </div>
                {!s.done && (
                  <Button kind="soft" size="sm" onClick={() => setAdding(s.kind)}>
                    Add
                  </Button>
                )}
              </div>
            ))}
          </section>
        )}

        {adding && (
          <div className="mt-4 max-w-4xl">
            <PlaceForm
              key={adding}
              initialKind={adding}
              places={places ?? []}
              practiceChannel={practiceChannel}
              emailOn={emailOn}
              onCancel={() => setAdding(null)}
              onSaved={async () => {
                setAdding(null);
                await load();
              }}
            />
          </div>
        )}

        {!places ? (
          <div className="mt-6 grid gap-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-24 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
            ))}
          </div>
        ) : places.length === 0 ? (
          !adding && (
            <div className="mt-6 rounded-2xl border border-dashed border-line-strong p-6 text-[15px] text-muted">
              No places yet. Start with the reception, then the exit and your rooms. A waiting room is optional: rooms that have
              one let Otto seat the patient there until the doctor calls them in.
            </div>
          )
        ) : (
          GROUPS.map((g) => {
            const list = places.filter((p) => p.kind === g.kind);
            if (!list.length) return null;
            return (
              <section key={g.kind} className="mt-8" aria-labelledby={`group-${g.kind}`}>
                <h2 id={`group-${g.kind}`} className="flex items-center gap-2 text-sm font-semibold text-muted">
                  <KindIcon kind={g.kind} size={18} /> {g.title}
                </h2>
                <ul className="mt-2 grid gap-3 xl:grid-cols-2">
                  {list.map((p) =>
                    editing === p.id ? (
                      <li key={p.id}>
                        <PlaceForm
                          place={p}
                          places={places}
                          practiceChannel={practiceChannel}
                          emailOn={emailOn}
                          onCancel={() => setEditing(null)}
                          onSaved={async () => {
                            setEditing(null);
                            await load();
                          }}
                        />
                      </li>
                    ) : (
                      <PlaceRow
                        key={p.id}
                        place={p}
                        places={places}
                        practiceChannel={practiceChannel}
                        onEdit={() => setEditing(p.id)}
                        reload={load}
                      />
                    ),
                  )}
                </ul>
              </section>
            );
          })
        )}
      </div>
    </main>
  );
}

function PlaceRow({
  place,
  places,
  practiceChannel,
  onEdit,
  reload,
}: {
  place: Place;
  places: Place[];
  practiceChannel: boolean;
  onEdit: () => void;
  reload: () => Promise<void>;
}) {
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const waiting = place.waitingPlaceId ? places.find((p) => p.id === place.waitingPlaceId) : undefined;
  const teams = place.teamsWebhookUrl ? "Own Teams channel" : practiceChannel ? "Practice Teams channel" : "No Teams channel";
  const chips = [
    place.public ? "Patients can ask for it" : null,
    place.selfCheckIn ? "Self check-in on Otto" : null,
    waiting ? `Waits in ${waiting.name}` : null,
    place.kind === "room" || place.kind === "other" ? `${place.avgMinutes} min per patient` : null,
    place.kind !== "exit" ? teams : null,
    place.notifyEmail ? `Email to ${place.notifyEmail}` : null,
    place.viaSpot ? `Detour via ${place.viaSpot}` : null,
  ].filter(Boolean) as string[];

  const remove = async () => {
    try {
      await api(`/api/places?id=${encodeURIComponent(place.id)}`, { method: "DELETE" });
      await reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not delete");
      setConfirm(false);
    }
  };

  return (
    <li className={`rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5 ${place.active ? "" : "opacity-70"}`}>
      <div className="flex flex-wrap items-start gap-x-4 gap-y-3">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary-soft text-primary">
          <KindIcon kind={place.kind} size={22} />
        </span>
        <div className="min-w-0 flex-1 basis-56">
          <h3 className="text-[17px] font-semibold break-words">
            {place.name}
            {place.room && <span className="ml-2 font-normal text-muted">Room {place.room}</span>}
            {!place.active && (
              <span className="ml-2 rounded-full bg-subtle px-2 py-0.5 text-xs font-semibold text-muted">Inactive</span>
            )}
          </h3>
          <p className="mt-0.5 text-sm text-muted">
            Map spot <span className="font-mono text-ink">{place.spot}</span>
          </p>
          {chips.length > 0 && (
            <ul className="mt-2 flex flex-wrap gap-1.5">
              {chips.map((c) => (
                <li key={c} className="max-w-full truncate rounded-full bg-subtle px-2.5 py-1 text-xs font-semibold text-muted">
                  {c}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="flex flex-wrap gap-2">
          {(place.kind === "room" || place.kind === "other" || place.kind === "waiting") && (
            <Link
              href={`/room/${place.id}`}
              className="press inline-flex h-11 items-center gap-1 rounded-xl px-3 text-[15px] font-semibold text-primary hover:bg-primary-soft"
            >
              Room view <ArrowRight size={16} aria-hidden />
            </Link>
          )}
          <Button onClick={onEdit}>
            <PencilSimple size={18} aria-hidden /> Edit
          </Button>
          {!confirm && (
            <Button kind="quiet" onClick={() => setConfirm(true)} aria-label={`Delete ${place.name}`}>
              <Trash size={18} aria-hidden />
            </Button>
          )}
        </div>
      </div>
      {confirm && (
        <div className="mt-4 flex flex-wrap items-center gap-2 rounded-xl bg-danger-soft p-3">
          <span className="flex-1 basis-48 text-[15px] font-medium text-danger">
            Delete {place.name}? Otto can&apos;t walk there any more.
          </span>
          <Button kind="danger" onClick={remove}>
            Yes, delete
          </Button>
          <Button onClick={() => setConfirm(false)}>Keep</Button>
        </div>
      )}
      {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}
    </li>
  );
}

function PlaceForm({
  place,
  initialKind,
  places,
  practiceChannel,
  emailOn,
  onCancel,
  onSaved,
}: {
  place?: Place;
  initialKind?: PlaceKind;
  places: Place[];
  practiceChannel: boolean;
  emailOn: boolean;
  onCancel: () => void;
  onSaved: () => Promise<void>;
}) {
  const [kind, setKind] = useState<PlaceKind>(place?.kind ?? initialKind ?? "room");
  const [name, setName] = useState(place?.name ?? "");
  const [room, setRoom] = useState(place?.room ?? "");
  const [spot, setSpot] = useState(place && place.spot !== place.name ? place.spot : "");
  const [minutes, setMinutes] = useState(place?.avgMinutes ?? DEFAULT_MINUTES);
  const [isPublic, setPublic] = useState(place?.public ?? false);
  const [selfCheckIn, setSelfCheckIn] = useState(place?.selfCheckIn ?? false);
  const [waitingId, setWaitingId] = useState(place?.waitingPlaceId ?? "");
  const [webhook, setWebhook] = useState(place?.teamsWebhookUrl ?? "");
  const [email, setEmail] = useState(place?.notifyEmail ?? "");
  const [via, setVia] = useState(place?.viaSpot ?? "");
  const [active, setActive] = useState(place?.active ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const roomKind = ROOM_KINDS.includes(kind);
  const alwaysPublic = ALWAYS_PUBLIC.includes(kind);
  const waitingRooms = places.filter((p) => p.kind === "waiting" && p.id !== place?.id);
  const formId = place ? `edit-${place.id}` : "new";

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body: Record<string, unknown> = {
      name,
      room,
      spot: spot.trim() || name,
      kind,
      public: alwaysPublic || isPublic,
      avgMinutes: minutes,
      teamsWebhookUrl: webhook,
      notifyEmail: email,
      viaSpot: via,
      waitingPlaceId: roomKind ? waitingId : "",
      selfCheckIn: roomKind && selfCheckIn,
      active,
    };
    try {
      if (place) await post("/api/places", { id: place.id, ...body }, "PATCH");
      else {
        if (!room) delete body.room;
        if (!webhook) delete body.teamsWebhookUrl;
        if (!email.trim()) delete body.notifyEmail;
        if (!via.trim()) delete body.viaSpot;
        if (!body.waitingPlaceId) delete body.waitingPlaceId;
        await post("/api/places", body);
      }
      await onSaved();
    } catch (err) {
      if (!(err instanceof LoginNeeded)) setError(err instanceof Error ? err.message : "Could not save");
    }
    setBusy(false);
  };

  return (
    <form
      onSubmit={save}
      className="rise rounded-2xl border-2 border-primary bg-surface p-4 shadow-panel sm:p-6"
      aria-label={place ? `Edit ${place.name}` : "New place"}
    >
      <h2 className="text-lg font-semibold">{place ? `Edit ${place.name}` : "New place"}</h2>

      <fieldset className="mt-4">
        <legend className="text-sm font-semibold">Kind</legend>
        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-5">
          {PLACE_KINDS.map((k) => (
            <label
              key={k}
              className={`press flex h-12 cursor-pointer items-center justify-center gap-2 rounded-xl border px-2 text-[15px] font-semibold ${
                kind === k
                  ? "border-primary bg-primary-soft text-primary-ink ring-1 ring-primary"
                  : "border-line text-muted hover:border-line-strong hover:text-ink"
              }`}
            >
              <input
                type="radio"
                name={`kind-${formId}`}
                value={k}
                checked={kind === k}
                onChange={() => setKind(k)}
                className="sr-only"
              />
              <KindIcon kind={k} size={18} />
              {KIND_LABEL[k]}
            </label>
          ))}
        </div>
        <p className="mt-2 text-sm text-muted">{KIND_HELP[kind]}</p>
      </fieldset>

      <div className="mt-5 grid gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div>
          <label htmlFor={`name-${formId}`} className="text-sm font-semibold">
            Name
          </label>
          <input
            id={`name-${formId}`}
            className={`${INPUT} mt-2`}
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={80}
            autoComplete="off"
            autoFocus={!place}
            placeholder={kind === "room" ? "The doctor or the room's name" : KIND_LABEL[kind]}
          />
        </div>
        <div>
          <label htmlFor={`room-${formId}`} className="text-sm font-semibold">
            Room number <span className="font-normal text-muted">(optional)</span>
          </label>
          <input
            id={`room-${formId}`}
            className={`${INPUT} mt-2`}
            value={room}
            onChange={(e) => setRoom(e.target.value)}
            maxLength={24}
            autoComplete="off"
          />
        </div>
      </div>

      <div className="mt-4">
        <label htmlFor={`spot-${formId}`} className="text-sm font-semibold">
          Map spot on Otto
        </label>
        <input
          id={`spot-${formId}`}
          className={`${INPUT} mt-2 font-mono`}
          value={spot}
          onChange={(e) => setSpot(e.target.value)}
          maxLength={80}
          autoComplete="off"
          placeholder={name || "Same as the name"}
          aria-describedby={`spot-help-${formId}`}
        />
        <p id={`spot-help-${formId}`} className="mt-1 text-sm text-muted">
          Exactly as the spot is called on Otto&apos;s map. Empty means the same as the name.
        </p>
      </div>

      <div className="mt-4">
        <label htmlFor={`via-${formId}`} className="text-sm font-semibold">
          Detour spot <span className="font-normal text-muted">(optional)</span>
        </label>
        <input
          id={`via-${formId}`}
          className={`${INPUT} mt-2 font-mono`}
          value={via}
          onChange={(e) => setVia(e.target.value)}
          maxLength={80}
          autoComplete="off"
          aria-describedby={`via-help-${formId}`}
        />
        <p id={`via-help-${formId}`} className="mt-1 text-sm text-muted">
          A spot on Otto&apos;s map for another way there. When the normal way stays blocked, Otto tries it once before it
          calls the team.
        </p>
      </div>

      {roomKind && (
        <div className="mt-4">
          <span id={`min-label-${formId}`} className="text-sm font-semibold">
            Minutes per patient
          </span>
          <div className="mt-2 flex items-center gap-2" role="group" aria-labelledby={`min-label-${formId}`}>
            <Button
              aria-label="Fewer minutes"
              className="w-12 px-0"
              onClick={() => setMinutes((m) => Math.max(MIN_MINUTES, m - 5))}
            >
              <Minus size={18} aria-hidden />
            </Button>
            <input
              type="number"
              inputMode="numeric"
              min={MIN_MINUTES}
              max={MAX_MINUTES}
              value={minutes}
              onChange={(e) => setMinutes(Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, Number(e.target.value) || MIN_MINUTES)))}
              className="h-12 w-20 shrink-0 rounded-xl border border-line-strong bg-surface px-2 text-center text-base tabular focus:border-primary focus:outline-none"
              aria-label="Minutes per patient"
            />
            <Button
              aria-label="More minutes"
              className="w-12 px-0"
              onClick={() => setMinutes((m) => Math.min(MAX_MINUTES, m + 5))}
            >
              <Plus size={18} aria-hidden />
            </Button>
            <span className="text-sm text-muted">for the wait estimate</span>
          </div>
        </div>
      )}

      {roomKind && (
        <div className="mt-4">
          <label htmlFor={`waiting-${formId}`} className="text-sm font-semibold">
            Waiting room
          </label>
          <select
            id={`waiting-${formId}`}
            className={`${INPUT} mt-2`}
            value={waitingId}
            onChange={(e) => setWaitingId(e.target.value)}
          >
            <option value="">None, Otto walks straight in</option>
            {waitingRooms.map((w) => (
              <option key={w.id} value={w.id}>
                {w.name}
              </option>
            ))}
          </select>
          {waitingRooms.length === 0 && (
            <p className="mt-1 text-sm text-muted">Add a place of kind Waiting room to pick one here.</p>
          )}
        </div>
      )}

      <div className="mt-4 divide-y divide-line rounded-xl border border-line px-4">
        {roomKind && (
          <Switch
            id={`self-${formId}`}
            checked={selfCheckIn}
            onChange={setSelfCheckIn}
            label="Patients can check in for it on Otto"
            description="Shown as a tile on the robot. Otto walks them to the waiting room and the room gets a Teams card."
          />
        )}
        <Switch
          id={`public-${formId}`}
          checked={alwaysPublic || isPublic}
          disabled={alwaysPublic}
          onChange={setPublic}
          label="Patients can ask Otto for it"
          description={
            alwaysPublic
              ? "Always on for the reception and the exit."
              : "Like toilets or the pharmacy. Doctor rooms are set by staff only."
          }
        />
        {place && (
          <Switch
            id={`active-${formId}`}
            checked={active}
            onChange={setActive}
            label="Active"
            description="Inactive places are hidden from the reception."
          />
        )}
      </div>

      {kind !== "exit" && (
        <div className="mt-4">
          <label htmlFor={`hook-${formId}`} className="text-sm font-semibold">
            Teams channel webhook <span className="font-normal text-muted">(optional)</span>
          </label>
          <input
            id={`hook-${formId}`}
            className={`${INPUT} mt-2`}
            value={webhook}
            onChange={(e) => setWebhook(e.target.value)}
            inputMode="url"
            autoComplete="off"
            placeholder="https://"
          />
          <p className="mt-1 text-sm text-muted">
            {practiceChannel
              ? "Empty means the practice channel gets the cards."
              : "Empty means no Teams card for this place (no practice channel is set up)."}
          </p>
        </div>
      )}

      {kind !== "exit" && (
        <div className="mt-4">
          <label htmlFor={`email-${formId}`} className="text-sm font-semibold">
            Email <span className="font-normal text-muted">(optional)</span>
          </label>
          <input
            id={`email-${formId}`}
            className={`${INPUT} mt-2`}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            type="text"
            inputMode="email"
            autoComplete="off"
            maxLength={1300}
            aria-describedby={`email-help-${formId}`}
          />
          <p id={`email-help-${formId}`} className="mt-1 text-sm text-muted">
            {emailOn
              ? "Gets the same message as the Teams card: ticket and room, never a name. Up to 5 addresses, separated by commas."
              : "Email is off on this server (RESEND_API_KEY and EMAIL_FROM are not set). You can already save an address."}
          </p>
        </div>
      )}

      {error && (
        <p role="alert" className="mt-4 rounded-xl bg-danger-soft px-4 py-3 text-[15px] font-medium text-danger">
          {error}
        </p>
      )}
      <div className="mt-5 flex flex-wrap gap-2">
        <Button kind="primary" size="lg" type="submit" disabled={busy || !name.trim()}>
          {busy ? "Saving" : place ? "Save changes" : "Add place"}
        </Button>
        <Button size="lg" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
