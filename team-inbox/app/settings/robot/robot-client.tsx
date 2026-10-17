"use client";

import { ArrowCounterClockwise, CheckCircle, Clock, FloppyDisk } from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { MODE_INFO, OTTO_MODES, type OttoMode } from "@/lib/modes";
import {
  CONFIG_GROUPS,
  type ConfigField,
  type ConfigGroupKey,
  type ConfigValue,
  type RobotSettings,
  checkValue,
  crossCheck,
  fieldsOf,
  showValue,
} from "@/lib/robot-config";
import { LoginNeeded, ROBOT, api, clockTime, post } from "@/lib/ui";
import { Button, Segmented, Switch } from "../../components/button";
import { modeLine, switchMode, useRobotMode } from "../../components/robot-mode";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";

type View = {
  robot: string;
  version: number;
  settings: RobotSettings;
  overrides: Record<string, unknown>;
  updatedAt: string | null;
  applied: { version: number; at: string } | null;
};

export function RobotSettingsClient() {
  const [view, setView] = useState<View | null>(null);
  const [draft, setDraft] = useState<RobotSettings | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const v = await api<View>(`/api/robot/config?robot=${ROBOT}`);
      setView(v);
      setDraft(JSON.parse(JSON.stringify(v.settings)));
      setError(null);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not load Otto's settings");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // otto confirms with its heartbeat; look again now and then while the page is open
  useEffect(() => {
    const t = setInterval(() => {
      if (document.visibilityState === "visible") api<View>(`/api/robot/config?robot=${ROBOT}`).then((v) => setView(v)).catch(() => undefined);
    }, 8000);
    return () => clearInterval(t);
  }, []);

  return (
    <section aria-labelledby="robot-settings-title">
      <div className="pb-4">
        <h2 id="robot-settings-title" className="text-xl font-semibold tracking-tight">
          Otto&apos;s settings
        </h2>
        <p className="mt-1 max-w-prose text-[15px] text-muted">
          These change Otto without a new app. Saving sends them to Otto right away; it shows when Otto took them. Out of range values
          are not saved, and Otto falls back to its built-in value for anything it can&apos;t use.
        </p>
      </div>
      {error && <p className="mb-4 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger">{error}</p>}

      <ModeCard />

      {view && <AppliedLine view={view} />}

      {!view || !draft ? (
        <div className="mt-6 grid gap-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-40 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
          ))}
        </div>
      ) : (
        <div className="mt-6 grid max-w-5xl gap-5">
          {CONFIG_GROUPS.map((g) => (
            <GroupCard
              key={g.key}
              group={g.key}
              title={g.title}
              help={g.help}
              saved={view.settings}
              draft={draft}
              overridden={Boolean(view.overrides[g.key])}
              onChange={(key, value) => setDraft((d) => (d ? { ...d, [g.key]: { ...d[g.key], [key]: value } } : d))}
              onSaved={(v) => {
                setView(v);
                setDraft((d) => (d ? { ...d, [g.key]: JSON.parse(JSON.stringify(v.settings[g.key])) } : d));
              }}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function ModeCard() {
  const mode = useRobotMode();
  const [busy, setBusy] = useState<OttoMode | null>(null);
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="max-w-5xl rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5">
      <h3 className="text-[17px] font-semibold">Mode</h3>
      <p className="mt-0.5 text-sm text-muted">
        Also at the top of every page. Only Normal guides patients. Demo plays the{" "}
        <Link href="/settings/demo" className="font-semibold text-primary hover:underline">
          demo script
        </Link>
        .
      </p>
      <div className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-4" role="radiogroup" aria-label="Otto's mode">
        {OTTO_MODES.map((m) => {
          const on = mode?.mode === m;
          return (
            <button
              key={m}
              type="button"
              role="radio"
              aria-checked={on}
              disabled={!mode || busy !== null}
              onClick={async () => {
                if (on) return;
                setBusy(m);
                setError(null);
                try {
                  await switchMode(m);
                } catch (e) {
                  if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not switch");
                }
                setBusy(null);
              }}
              className={`press rounded-xl border p-3 text-left ${on ? "border-primary bg-primary-soft ring-1 ring-primary" : "border-line hover:border-line-strong hover:bg-subtle"}`}
            >
              <span className="block font-semibold">{busy === m ? "Switching" : MODE_INFO[m].label}</span>
              <span className="mt-0.5 block text-sm text-muted">{MODE_INFO[m].help}</span>
            </button>
          );
        })}
      </div>
      {mode && <p className="mt-3 text-sm text-muted">Otto: {modeLine(mode)}</p>}
      {error && <p className="mt-2 text-[15px] font-medium text-danger">{error}</p>}
    </div>
  );
}

function AppliedLine({ view }: { view: View }) {
  const ok = view.applied && view.applied.version >= view.version;
  return (
    <p className={`mt-4 flex max-w-5xl items-start gap-2 rounded-xl px-4 py-3 text-[15px] ${ok ? "bg-ok-soft" : "bg-subtle text-muted"}`} role="status">
      {ok ? <CheckCircle size={20} weight="fill" aria-hidden className="mt-px shrink-0 text-ok" /> : <Clock size={20} aria-hidden className="mt-px shrink-0" />}
      <span>
        {view.version === 0
          ? "Otto runs its built-in settings. Change anything below to send Otto your own."
          : `Saved as version ${view.version}${view.updatedAt ? ` at ${clockTime(view.updatedAt)}` : ""}. `}
        {view.version > 0 &&
          (ok
            ? `Otto applied it at ${clockTime(view.applied!.at)}.`
            : view.applied
              ? `Otto still runs version ${view.applied.version} (since ${clockTime(view.applied.at)}). It takes the new one within a minute, or right away when it is online.`
              : "Otto has not confirmed it yet. It fetches its settings when it starts, every minute, and right away after a save.")}
      </span>
    </p>
  );
}

function GroupCard({
  group,
  title,
  help,
  saved,
  draft,
  overridden,
  onChange,
  onSaved,
}: {
  group: ConfigGroupKey;
  title: string;
  help: string;
  saved: RobotSettings;
  draft: RobotSettings;
  overridden: boolean;
  onChange: (key: string, value: ConfigValue) => void;
  onSaved: (v: View) => void;
}) {
  const [busy, setBusy] = useState<"save" | "reset" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const fields = fieldsOf(group);
  const dirty = JSON.stringify(saved[group]) !== JSON.stringify(draft[group]);
  // a value that is wrong on its own wins over the rules for values together (speeds, check times)
  const problems = Object.fromEntries([
    ...(group === "walk" ? Object.entries(crossCheck(draft.walk)).map(([k, v]) => [k.replace(/^walk\./, ""), v]) : []),
    ...fields.flatMap((f) => {
      const r = checkValue(f, draft[group][f.key]);
      return "error" in r ? [[f.key, r.error]] : [];
    }),
  ]) as Record<string, string>;

  const send = async (body: Record<string, unknown>, kind: "save" | "reset") => {
    setBusy(kind);
    setError(null);
    setDone(null);
    try {
      const v = await post<View>("/api/robot/config", { robot: ROBOT, ...body }, "PATCH");
      onSaved(v);
      setDone(kind === "reset" ? "Back to the defaults. Sent to Otto." : "Saved. Sent to Otto.");
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not save");
    }
    setBusy(null);
  };

  return (
    <form
      noValidate
      className="rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5"
      aria-labelledby={`group-${group}`}
      onSubmit={(e) => {
        e.preventDefault();
        if (!Object.keys(problems).length) send({ settings: { [group]: draft[group] } }, "save");
      }}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 id={`group-${group}`} className="text-[17px] font-semibold">
            {title}
          </h3>
          <p className="mt-0.5 text-sm text-muted">{help}</p>
        </div>
        <Button kind="ghost" size="sm" disabled={!overridden || busy !== null} onClick={() => send({ reset: group }, "reset")}>
          <ArrowCounterClockwise size={16} aria-hidden /> {busy === "reset" ? "Resetting" : "Reset to defaults"}
        </Button>
      </div>
      <div className="mt-2 divide-y divide-line">
        {fields.map((f) => (
          <FieldRow key={f.key} field={f} group={group} value={draft[group][f.key]} problem={problems[f.key]} onChange={(v) => onChange(f.key, v)} />
        ))}
      </div>
      {error && (
        <p role="alert" className="mt-3 rounded-xl bg-danger-soft px-4 py-3 text-[15px] font-medium text-danger">
          {error}
        </p>
      )}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button kind="primary" type="submit" disabled={!dirty || busy !== null || Object.keys(problems).length > 0}>
          <FloppyDisk size={18} aria-hidden /> {busy === "save" ? "Saving" : "Save"}
        </Button>
        {dirty && (
          <Button kind="ghost" onClick={() => fields.forEach((f) => onChange(f.key, saved[group][f.key]))}>
            Undo changes
          </Button>
        )}
        {done && !dirty && (
          <span className="text-sm text-ok" role="status">
            {done}
          </span>
        )}
      </div>
    </form>
  );
}

function FieldRow({
  field: f,
  group,
  value,
  problem,
  onChange,
}: {
  field: ConfigField;
  group: string;
  value: ConfigValue;
  problem?: string;
  onChange: (v: ConfigValue) => void;
}) {
  const id = `cfg-${group}-${f.key}`;
  const changed = JSON.stringify(value) !== JSON.stringify(f.default);
  const defaultNote = changed ? <span className="text-muted"> Default: {showValue(f, f.default)}.</span> : null;

  if (f.type === "bool") {
    return (
      <Switch
        id={id}
        checked={value === true}
        onChange={onChange}
        label={f.label}
        description={
          <>
            {f.help}
            {defaultNote}
          </>
        }
      />
    );
  }
  return (
    <div className="grid gap-2 py-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,280px)] sm:items-center sm:gap-6">
      <div className="min-w-0">
        <label htmlFor={id} className="block text-[15px] font-semibold">
          {f.label}
          {changed && <span className="ml-2 rounded-full bg-primary-soft px-2 py-0.5 text-xs font-semibold text-primary-ink">changed</span>}
        </label>
        <p className="text-sm text-muted">
          {f.help}
          {f.type === "number" && (
            <span>
              {" "}
              {f.min} to {f.max}
              {f.unit ? ` ${f.unit}` : ""}.
            </span>
          )}
          {defaultNote}
        </p>
        {problem && <p className="mt-1 text-sm font-medium text-danger">{problem}</p>}
      </div>
      <div>
        {f.type === "number" ? (
          <div className="flex items-center gap-2">
            <input
              id={id}
              type="number"
              inputMode="decimal"
              min={f.min}
              max={f.max}
              step={f.step}
              value={typeof value === "number" ? value : value === null ? "" : String(value)}
              placeholder={f.optional ? f.emptyLabel : undefined}
              onChange={(e) => onChange(e.target.value === "" ? (f.optional ? null : "") : Number(e.target.value))}
              className={`${INPUT} tabular ${problem ? "border-danger" : ""}`}
              aria-invalid={Boolean(problem)}
            />
            {f.unit && <span className="w-12 shrink-0 text-sm text-muted">{f.unit}</span>}
          </div>
        ) : f.type === "choice" ? (
          <Segmented label={f.label} value={String(value)} onChange={(v) => onChange(v)} options={f.options} />
        ) : f.type === "list" ? (
          <input
            id={id}
            value={Array.isArray(value) ? value.join(", ") : value === null ? "" : String(value)}
            placeholder={f.optional ? f.emptyLabel : undefined}
            onChange={(e) => onChange(f.optional && !e.target.value.trim() ? null : e.target.value)}
            className={`${INPUT} font-mono placeholder:font-sans ${problem ? "border-danger" : ""}`}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={Boolean(problem)}
          />
        ) : (
          <input
            id={id}
            value={String(value)}
            maxLength={f.maxLength}
            onChange={(e) => onChange(e.target.value)}
            className={`${INPUT} ${problem ? "border-danger" : ""}`}
            autoComplete="off"
            aria-invalid={Boolean(problem)}
          />
        )}
      </div>
    </div>
  );
}
