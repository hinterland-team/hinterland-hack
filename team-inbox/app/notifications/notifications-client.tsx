"use client";

import { ArrowRight, Bell, CheckCircle, Info, MapPin, PaperPlaneTilt, PencilSimple, Plus, Trash, WarningCircle } from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  CHANNEL_INFO,
  CHANNEL_TYPES,
  type ChannelField,
  type ChannelResult,
  type ChannelType,
  type ChannelView,
  MAX_LABEL,
  type NotificationsView,
  channelChipName,
} from "@/lib/channel-types";
import type { Place } from "@/lib/types";
import { KIND_LABEL, LoginNeeded, PAGE, api, clockTime, post } from "@/lib/ui";
import { Button, Segmented, Switch } from "../components/button";
import { ChannelIcon } from "../components/channel-icon";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";

const LINK = "press inline-flex h-11 items-center gap-1 rounded-xl px-3 text-[15px] font-semibold text-primary hover:bg-primary-soft";

function errorText(e: unknown, fallback: string) {
  return e instanceof Error ? e.message : fallback;
}

// the name on cards and switches
function titleOf(c: ChannelView, places: Place[]) {
  if (c.source === "env") return "Practice channel";
  if (c.source === "place") return places.find((p) => p.id === c.placeId)?.name ?? c.label;
  return c.label;
}

function typeLine(c: ChannelView) {
  if (c.source === "env") return "Microsoft Teams, from .env.local";
  if (c.source === "place") return c.type === "teams" ? "Own Teams channel, set on Places" : "Email, set on Places";
  return CHANNEL_INFO[c.type].title;
}

const chipName = channelChipName;

export function NotificationsClient() {
  const [view, setView] = useState<NotificationsView | null>(null);
  const [places, setPlaces] = useState<Place[] | null>(null);
  const [adding, setAdding] = useState<ChannelType | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [v, p] = await Promise.all([api<NotificationsView>("/api/channels"), api<Place[]>("/api/places")]);
      setView(v);
      setPlaces(p);
      setError(null);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not load the channels"));
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const own = view?.channels.filter((c) => c.source !== "place") ?? [];
  const fromPlaces = view?.channels.filter((c) => c.source === "place") ?? [];
  const edited = editing ? own.find((c) => c.id === editing) : undefined;
  // the form opens above the list (like a new channel), so the list keeps its layout
  const formTop = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (editing) formTop.current?.scrollIntoView({ block: "start" });
  }, [editing]);

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="flex flex-wrap items-end justify-between gap-4 pt-6 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">Notifications</h1>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            Where the team hears about a patient on the way, a visitor in the waiting room and Otto asking for help. Every message
            has the ticket, the place, the pace and two links. Never a name.
          </p>
        </div>
        {!adding && !edited && (
          <Button
            kind="primary"
            size="lg"
            onClick={() => {
              setEditing(null);
              setAdding("teams");
            }}
          >
            <Plus size={20} weight="bold" aria-hidden /> Add a channel
          </Button>
        )}
      </div>

      {error && <p className="mb-4 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger">{error}</p>}

      {view?.memoryStore && (
        <p className="mb-4 flex max-w-4xl items-start gap-2 rounded-xl bg-subtle px-4 py-3 text-[15px] text-muted">
          <Info size={20} aria-hidden className="mt-px shrink-0" />
          This server keeps everything in memory, the channels too: after a restart add them again, or set DATABASE_URL to keep
          them.
        </p>
      )}

      {edited && view && places && (
        <div ref={formTop} className="mb-6 max-w-4xl scroll-mt-4">
          <ChannelForm
            key={edited.id}
            channel={edited}
            title={titleOf(edited, places)}
            emailEnv={view.emailEnv}
            onCancel={() => setEditing(null)}
            onSaved={async () => {
              setEditing(null);
              await load();
            }}
          />
        </div>
      )}

      {adding && view && (
        <div className="mb-6 max-w-4xl">
          <ChannelForm
            key={adding}
            initialType={adding}
            emailEnv={view.emailEnv}
            onCancel={() => setAdding(null)}
            onSaved={async () => {
              setAdding(null);
              await load();
            }}
          />
        </div>
      )}

      <section aria-labelledby="channels-title" className="mt-2">
        <h2 id="channels-title" className="flex items-center gap-2 text-sm font-semibold text-muted">
          <Bell size={18} aria-hidden /> Channels
        </h2>
        {!view || !places ? (
          <div className="mt-2 grid gap-3 xl:grid-cols-2">
            {[0, 1].map((i) => (
              <div key={i} className="h-36 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
            ))}
          </div>
        ) : own.length === 0 ? (
          !adding && <EmptyChannels onPick={setAdding} />
        ) : (
          <ul className="mt-2 grid gap-3 xl:grid-cols-2">
            {own.map((c) => (
              <ChannelCard
                key={c.id}
                channel={c}
                title={titleOf(c, places)}
                editing={editing === c.id}
                onEdit={
                  c.source === "dashboard"
                    ? () => {
                        setAdding(null);
                        setEditing(c.id);
                      }
                    : undefined
                }
                reload={load}
              />
            ))}
          </ul>
        )}
      </section>

      {view && places && fromPlaces.length > 0 && (
        <section aria-labelledby="place-channels-title" className="mt-8">
          <h2 id="place-channels-title" className="flex items-center gap-2 text-sm font-semibold text-muted">
            <MapPin size={18} aria-hidden /> Set on the Places page
          </h2>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            A place&apos;s own Teams webhook gets its messages instead of the practice channel. Its email address gets them as well.
          </p>
          <ul className="mt-2 grid gap-3 xl:grid-cols-2">
            {fromPlaces.map((c) => (
              <ChannelCard key={c.id} channel={c} title={titleOf(c, places)} reload={load} />
            ))}
          </ul>
        </section>
      )}

      {view && places && <Routing view={view} places={places} reload={load} />}
    </main>
  );
}

function EmptyChannels({ onPick }: { onPick: (type: ChannelType) => void }) {
  return (
    <div className="mt-2 rounded-2xl border border-dashed border-line-strong p-5 sm:p-6">
      <p className="max-w-prose text-[15px] text-muted">
        No channels yet. The reception board shows every patient anyway. Add a channel so the team also hears about it where they
        already are:
      </p>
      <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-7">
        {CHANNEL_TYPES.map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => onPick(t)}
            className="press flex h-12 items-center justify-center gap-2 rounded-xl border border-line bg-surface px-2 text-[15px] font-semibold text-ink hover:border-line-strong hover:bg-subtle"
          >
            <ChannelIcon type={t} size={18} className="text-primary" />
            {CHANNEL_INFO[t].short}
          </button>
        ))}
      </div>
    </div>
  );
}

function sentence(text: string) {
  if (!text) return "";
  const t = text.charAt(0).toUpperCase() + text.slice(1);
  return /[.!?)]$/.test(t) ? t : `${t}.`;
}

function ResultLine({ result }: { result: ChannelResult }) {
  const what = result.test ? (result.ok ? "Test sent" : "Test failed") : result.ok ? "Last message sent" : "Last message failed";
  return (
    <p
      role="status"
      className={`mt-3 flex items-start gap-2 rounded-xl px-3 py-2.5 text-[15px] ${
        result.ok ? "bg-ok-soft text-ink" : "bg-danger-soft text-danger"
      }`}
    >
      {result.ok ? (
        <CheckCircle size={20} weight="fill" aria-hidden className="mt-px shrink-0 text-ok" />
      ) : (
        <WarningCircle size={20} weight="fill" aria-hidden className="mt-px shrink-0" />
      )}
      <span className="min-w-0 break-words">
        <span className="font-semibold">
          {what} at {clockTime(result.at)}.
        </span>{" "}
        {sentence(result.text)}
      </span>
    </p>
  );
}

function ChannelCard({
  channel,
  title,
  editing = false,
  onEdit,
  reload,
}: {
  channel: ChannelView;
  title: string;
  editing?: boolean;
  onEdit?: () => void;
  reload: () => Promise<void>;
}) {
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<ChannelResult | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const shown = result ?? channel.last ?? null;
  const chips = channel.active ? [] : ["Off"];

  const test = async () => {
    setTesting(true);
    setError(null);
    try {
      const r = await post<{ ok: boolean; text: string; at: string }>("/api/channels/test", { id: channel.id });
      setResult({ ...r, test: true });
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not send the test"));
    }
    setTesting(false);
  };

  const setDefault = async (value: boolean) => {
    setSaving(true);
    setError(null);
    try {
      await post("/api/channels", { id: channel.id, isDefault: value }, "PATCH");
      await reload();
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not save"));
    }
    setSaving(false);
  };

  const remove = async () => {
    try {
      await api(`/api/channels?id=${encodeURIComponent(channel.id)}`, { method: "DELETE" });
      await reload();
    } catch (e) {
      setConfirm(false);
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not remove"));
    }
  };

  return (
    <li
      className={`rounded-2xl border bg-surface p-4 shadow-panel sm:p-5 ${editing ? "border-primary ring-1 ring-primary" : "border-line"} ${
        channel.active ? "" : "opacity-70"
      }`}
    >
      <div className="flex flex-wrap items-start gap-x-4 gap-y-3">
        <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary-soft text-primary">
          <ChannelIcon type={channel.type} size={22} />
        </span>
        <div className="min-w-0 flex-1 basis-56">
          <h3 className="text-[17px] font-semibold break-words">{title}</h3>
          <p className="text-sm text-muted">{typeLine(channel)}</p>
          {channel.summary && <p className="mt-1 truncate font-mono text-sm text-muted">{channel.summary}</p>}
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
          <Button onClick={test} disabled={testing}>
            <PaperPlaneTilt size={18} aria-hidden /> {testing ? "Sending" : "Send test"}
          </Button>
          {onEdit && (
            <Button onClick={onEdit} disabled={editing}>
              <PencilSimple size={18} aria-hidden /> {editing ? "Editing" : "Edit"}
            </Button>
          )}
          {channel.source === "dashboard" && !confirm && (
            <Button kind="quiet" onClick={() => setConfirm(true)} aria-label={`Remove ${title}`}>
              <Trash size={18} aria-hidden />
            </Button>
          )}
          {channel.source === "place" && (
            <Link href="/places" className={LINK}>
              Edit on Places <ArrowRight size={16} aria-hidden />
            </Link>
          )}
        </div>
      </div>

      {channel.off && (
        <p className="mt-3 flex items-start gap-2 rounded-xl bg-warn-soft px-3 py-2.5 text-[15px] text-warn">
          <Info size={20} aria-hidden className="mt-px shrink-0" />
          {channel.off}
        </p>
      )}

      {channel.source !== "place" && (
        <div className="mt-3 border-t border-line">
          <Switch
            id={`default-${channel.id}`}
            checked={channel.isDefault}
            disabled={saving}
            onChange={setDefault}
            label="Default for all places"
            description="And for help requests from Otto."
          />
        </div>
      )}

      {shown && <ResultLine result={shown} />}
      {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}

      {confirm && (
        <div className="mt-4 flex flex-wrap items-center gap-2 rounded-xl bg-danger-soft p-3">
          <span className="flex-1 basis-48 text-[15px] font-medium text-danger">
            Remove {title}? The places that picked it go back to the default channels if it was their only one.
          </span>
          <Button kind="danger" onClick={remove}>
            Yes, remove
          </Button>
          <Button onClick={() => setConfirm(false)}>Keep</Button>
        </div>
      )}
    </li>
  );
}

function FieldInput({
  field,
  formId,
  value,
  saved,
  optional,
  cleared,
  onChange,
  onClear,
}: {
  field: ChannelField;
  formId: string;
  value: string;
  saved?: string;
  optional: boolean;
  cleared: boolean;
  onChange: (value: string) => void;
  onClear: () => void;
}) {
  const id = `${field.key}-${formId}`;
  const keep = Boolean(saved) && !cleared;
  const help = keep ? `Saved (${saved}). Leave it empty to keep it.` : field.help;
  const mode = field.kind === "url" ? "url" : field.kind === "tel" ? "tel" : field.kind === "email" ? "email" : "text";
  return (
    <div className="mt-4">
      <label htmlFor={id} className="text-sm font-semibold">
        {field.label} {optional && <span className="font-normal text-muted">(optional)</span>}
      </label>
      <input
        id={id}
        className={`${INPUT} mt-2 ${field.secret || field.kind === "url" ? "font-mono text-[15px] placeholder:font-sans" : ""}`}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        type="text"
        inputMode={mode}
        autoComplete="off"
        autoCapitalize="none"
        spellCheck={false}
        required={!optional && !keep}
        placeholder={keep ? "Saved. Type a new one to replace it" : field.placeholder}
        aria-describedby={help ? `${id}-help` : undefined}
      />
      {help && (
        <p id={`${id}-help`} className="mt-1 text-sm break-words text-muted">
          {help}
        </p>
      )}
      {keep && field.optional && (
        <Button kind="ghost" size="sm" className="mt-1" onClick={onClear}>
          Remove the saved {field.label.toLowerCase()}
        </Button>
      )}
    </div>
  );
}

function ChannelForm({
  channel,
  title,
  initialType,
  emailEnv,
  onCancel,
  onSaved,
}: {
  channel?: ChannelView;
  title?: string;
  initialType?: ChannelType;
  emailEnv: NotificationsView["emailEnv"];
  onCancel: () => void;
  onSaved: () => Promise<void>;
}) {
  const [type, setType] = useState<ChannelType>(channel?.type ?? initialType ?? "teams");
  const info = CHANNEL_INFO[type];
  const [label, setLabel] = useState(channel && channel.label !== CHANNEL_INFO[channel.type].title ? channel.label : "");
  const [values, setValues] = useState<Record<string, string>>(channel ? { ...channel.values } : {});
  const [cleared, setCleared] = useState<string[]>([]);
  const [isDefault, setDefault] = useState(channel?.isDefault ?? true);
  const [active, setActive] = useState(channel?.active ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const formId = channel ? `edit-${channel.id}` : "new";

  const optional = (f: ChannelField) =>
    Boolean(f.optional) || (type === "email" && ((f.key === "apiKey" && emailEnv.apiKey) || (f.key === "from" && emailEnv.from)));

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const config: Record<string, string | null> = {};
      for (const f of info.fields) {
        const v = values[f.key]?.trim() ?? "";
        if (cleared.includes(f.key) && !v) config[f.key] = null;
        else if (v || !f.secret) config[f.key] = v;
      }
      if (channel) await post("/api/channels", { id: channel.id, label: label.trim(), config, isDefault, active }, "PATCH");
      else await post("/api/channels", { type, label: label.trim(), config, isDefault });
      await onSaved();
    } catch (err) {
      if (!(err instanceof LoginNeeded)) setError(errorText(err, "Could not save"));
    }
    setBusy(false);
  };

  const name = channel ? (title ?? channel.label) : "";
  return (
    <form
      onSubmit={save}
      className="rise rounded-2xl border-2 border-primary bg-surface p-4 shadow-panel sm:p-6"
      aria-label={channel ? `Edit ${name}` : "New channel"}
    >
      <h2 className="text-lg font-semibold">{channel ? `Edit ${name}` : "New channel"}</h2>

      {!channel && (
        <fieldset className="mt-4">
          <legend className="text-sm font-semibold">Where should the messages go?</legend>
          <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-7">
            {CHANNEL_TYPES.map((t) => (
              <label
                key={t}
                className={`press flex h-12 cursor-pointer items-center justify-center gap-2 rounded-xl border px-2 text-[15px] font-semibold ${
                  type === t
                    ? "border-primary bg-primary-soft text-primary-ink ring-1 ring-primary"
                    : "border-line text-muted hover:border-line-strong hover:text-ink"
                }`}
              >
                <input
                  type="radio"
                  name={`type-${formId}`}
                  value={t}
                  checked={type === t}
                  onChange={() => {
                    setType(t);
                    setValues({});
                    setError(null);
                  }}
                  className="sr-only"
                />
                <ChannelIcon type={t} size={18} />
                {CHANNEL_INFO[t].short}
              </label>
            ))}
          </div>
        </fieldset>
      )}

      <div className="mt-4 rounded-xl bg-subtle p-4">
        <p className="text-[15px] font-semibold">{info.what}</p>
        <ol className="mt-2 grid list-decimal gap-1 pl-5 text-[15px] text-muted">
          {info.how.map((h) => (
            <li key={h} className="break-words">
              {h}
            </li>
          ))}
        </ol>
        {info.note && (
          <p className="mt-3 flex items-start gap-2 text-sm text-ink">
            <Info size={18} aria-hidden className="mt-px shrink-0 text-primary" />
            {info.note}
          </p>
        )}
      </div>

      <div className="mt-4">
        <label htmlFor={`label-${formId}`} className="text-sm font-semibold">
          Name <span className="font-normal text-muted">(optional)</span>
        </label>
        <input
          id={`label-${formId}`}
          className={`${INPUT} mt-2`}
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          maxLength={MAX_LABEL}
          autoComplete="off"
          autoFocus={!channel}
          placeholder={info.title}
          aria-describedby={`label-help-${formId}`}
        />
        <p id={`label-help-${formId}`} className="mt-1 text-sm text-muted">
          Shown here and on the board, for example Front desk or Dr. Blue&apos;s phone.
        </p>
      </div>
      {info.fields.map((f) => (
        <FieldInput
          key={`${type}-${f.key}`}
          field={f}
          formId={formId}
          value={values[f.key] ?? ""}
          saved={f.secret ? channel?.secrets[f.key] : undefined}
          optional={optional(f)}
          cleared={cleared.includes(f.key)}
          onChange={(v) => setValues((old) => ({ ...old, [f.key]: v }))}
          onClear={() => setCleared((old) => [...old, f.key])}
        />
      ))}

      <div className="mt-4 divide-y divide-line rounded-xl border border-line px-4">
        <Switch
          id={`default-${formId}`}
          checked={isDefault}
          onChange={setDefault}
          label="Default for all places"
          description="Places without their own choice get its messages, and so do help requests from Otto."
        />
        {channel && (
          <Switch
            id={`active-${formId}`}
            checked={active}
            onChange={setActive}
            label="On"
            description="When it is off it gets nothing, also not where a place picked it."
          />
        )}
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-xl bg-danger-soft px-4 py-3 text-[15px] font-medium text-danger">
          {error}
        </p>
      )}
      <div className="mt-5 flex flex-wrap gap-2">
        <Button kind="primary" size="lg" type="submit" disabled={busy}>
          {busy ? "Saving" : channel ? "Save changes" : "Add channel"}
        </Button>
        <Button size="lg" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function Routing({ view, places, reload }: { view: NotificationsView; places: Place[]; reload: () => Promise<void> }) {
  const rows = places.filter((p) => p.kind !== "exit" && p.active);
  const byId = new Map(view.channels.map((c) => [c.id, c]));
  const choosable = view.channels.filter((c) => c.source !== "place" && c.active);
  const help = view.help.map((id) => byId.get(id)).filter((c): c is ChannelView => Boolean(c));
  return (
    <section aria-labelledby="routing-title" className="mt-10">
      <h2 id="routing-title" className="flex items-center gap-2 text-sm font-semibold text-muted">
        <MapPin size={18} aria-hidden /> Who gets which messages
      </h2>
      <p className="mt-1 max-w-prose text-[15px] text-muted">
        Each place uses the default channels unless you choose its own. A place gets the message when a patient is on the way to
        it, or sits in its waiting room.
      </p>
      {view.channels.length === 0 && rows.length > 0 ? (
        <p className="mt-3 rounded-2xl border border-dashed border-line-strong p-5 text-[15px] text-muted">
          Add a channel first. Then every place gets its messages there, or you choose per place here.
        </p>
      ) : rows.length === 0 ? (
        <p className="mt-3 rounded-2xl border border-dashed border-line-strong p-5 text-[15px] text-muted">
          No places yet.{" "}
          <Link href="/places" className="font-semibold text-primary hover:underline">
            Add them on the Places page
          </Link>
          , then choose here who hears about them.
        </p>
      ) : (
        <ul className="mt-3 grid gap-3 xl:grid-cols-2">
          {rows.map((p) => (
            <PlaceRouting
              key={`${p.id}-${(p.channelIds ?? ["default"]).join(",")}`}
              place={p}
              list={(view.routing[p.id] ?? []).map((id) => byId.get(id)).filter((c): c is ChannelView => Boolean(c))}
              choosable={choosable}
              reload={reload}
            />
          ))}
          <li className="rounded-2xl border border-dashed border-line-strong p-4 sm:p-5">
            <h3 className="text-[17px] font-semibold">Help requests from Otto</h3>
            <p className="mt-0.5 text-sm text-muted">Always the default channels</p>
            <ChannelChips list={help} empty="No default channel: help requests only show on the reception board." />
          </li>
        </ul>
      )}
    </section>
  );
}

function ChannelChips({ list, empty }: { list: ChannelView[]; empty: string }) {
  if (!list.length) return <p className="mt-2 text-sm text-muted">{empty}</p>;
  return (
    <ul className="mt-2 flex flex-wrap gap-1.5">
      {list.map((c) => (
        <li
          key={c.id}
          className="inline-flex max-w-full items-center gap-1.5 rounded-full bg-subtle px-2.5 py-1 text-xs font-semibold text-muted"
        >
          <ChannelIcon type={c.type} size={14} className="shrink-0" />
          <span className="truncate">{chipName(c)}</span>
        </li>
      ))}
    </ul>
  );
}

function PlaceRouting({
  place,
  list,
  choosable,
  reload,
}: {
  place: Place;
  list: ChannelView[];
  choosable: ChannelView[];
  reload: () => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [mode, setMode] = useState<"default" | "own">("default");
  const [picked, setPicked] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const defaults = choosable.filter((c) => c.isDefault);

  // starts from what is saved now: its own picks, or the current default channels
  const start = () => {
    setMode(place.channelIds ? "own" : "default");
    setPicked(place.channelIds ?? defaults.map((c) => c.id));
    setError(null);
    setEditing(true);
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await post("/api/places", { id: place.id, channelIds: mode === "own" ? picked : null }, "PATCH");
      await reload();
      setEditing(false);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(errorText(e, "Could not save"));
    }
    setBusy(false);
  };

  const cancel = () => {
    setError(null);
    setEditing(false);
  };

  return (
    <li className="rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-3">
        <div className="min-w-0 flex-1 basis-56">
          <h3 className="text-[17px] font-semibold break-words">
            {place.name}
            {place.room && <span className="ml-2 font-normal text-muted">Room {place.room}</span>}
          </h3>
          <p className="mt-0.5 text-sm text-muted">
            {KIND_LABEL[place.kind]}, {place.channelIds ? "own choice" : "default channels"}
          </p>
          <ChannelChips list={list} empty="No channel: only the reception board shows its patients." />
        </div>
        {!editing && <Button onClick={start}>Change</Button>}
      </div>

      {editing && (
        <div className="mt-4 rounded-xl border border-line p-3 sm:p-4" role="group" aria-label={`Channels for ${place.name}`}>
          <Segmented
            label={`Channels for ${place.name}`}
            value={mode}
            onChange={setMode}
            options={[
              { value: "default", label: "Default channels" },
              { value: "own", label: "Own choice" },
            ]}
          />
          {mode === "default" ? (
            <p className="mt-3 text-[15px] text-muted">
              {defaults.length
                ? `Now: ${defaults.map(chipName).join(", ")}. When the defaults change, this place follows.`
                : "There is no default channel yet. Mark one as default above, or choose this place's own."}
            </p>
          ) : choosable.length ? (
            <div className="mt-3 divide-y divide-line rounded-xl border border-line px-4">
              {choosable.map((c) => (
                <Switch
                  key={c.id}
                  id={`pick-${place.id}-${c.id}`}
                  checked={picked.includes(c.id)}
                  onChange={(on) => setPicked((old) => (on ? [...old, c.id] : old.filter((x) => x !== c.id)))}
                  icon={<ChannelIcon type={c.type} size={20} />}
                  label={chipName(c)}
                  description={CHANNEL_INFO[c.type].title}
                />
              ))}
            </div>
          ) : (
            <p className="mt-3 text-[15px] text-muted">Add a channel first.</p>
          )}
          {(place.teamsWebhookUrl || place.notifyEmail) && (
            <p className="mt-3 flex items-start gap-2 text-sm text-muted">
              <Info size={18} aria-hidden className="mt-px shrink-0" />
              {place.teamsWebhookUrl && place.notifyEmail
                ? "Its own Teams webhook and its email address (set on Places) always get its messages too."
                : place.teamsWebhookUrl
                  ? "Its own Teams webhook (set on Places) always gets its messages too, instead of the practice channel."
                  : "Its email address (set on Places) always gets its messages too."}
            </p>
          )}
          {error && <p className="mt-3 text-[15px] font-medium text-danger">{error}</p>}
          <div className="mt-3 flex flex-wrap gap-2">
            <Button kind="primary" onClick={save} disabled={busy}>
              {busy ? "Saving" : "Save"}
            </Button>
            <Button onClick={cancel}>Cancel</Button>
          </div>
        </div>
      )}
    </li>
  );
}
