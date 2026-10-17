"use client";

import { ArrowCounterClockwise, ArrowDown, ArrowRight, ArrowUp, CheckCircle, FloppyDisk, Plus, Trash } from "@phosphor-icons/react";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  DEMO_PARTS,
  type DemoPace,
  type DemoPart,
  type DemoScript,
  type DemoStep,
  CLOSE_MAX_S,
  FELL_BACK_AFTER_S,
  GLANCE_DEG,
  MAX_LINE,
  MAX_STEPS,
  MAX_WAIT_S,
  PART_INFO,
  PERSON_M,
  type ScriptErrors,
  checkScript,
  patientWaitS,
  resolvePlace,
} from "@/lib/demo-script";
import type { Place } from "@/lib/types";
import { LoginNeeded, api, clockTime, post } from "@/lib/ui";
import { Button, Segmented } from "../../components/button";

const INPUT =
  "h-12 w-full min-w-0 rounded-xl border border-line-strong bg-surface px-4 text-base placeholder:text-muted focus:border-primary focus:outline-none";
const CARD = "rounded-2xl border border-line bg-surface p-4 shadow-panel sm:p-5";
// selects and small inputs inside a step row (no full width)
const SELECT = "h-10 rounded-xl border border-line-strong bg-surface px-3 text-base placeholder:text-muted focus:border-primary focus:outline-none";

type Info = { script: DemoScript; isDefault: boolean; updatedAt: string | null };
// steps get a key for the editor only (moving and removing keep the right rows)
type Row = { key: string; step: DemoStep };
type Draft = Omit<DemoScript, "parts"> & { parts: Record<DemoPart, Row[]> };

let nextKey = 0;
const key = () => `s${++nextKey}`;

function toDraft(script: DemoScript): Draft {
  const parts = {} as Record<DemoPart, Row[]>;
  for (const p of DEMO_PARTS) parts[p] = (script.parts[p] ?? []).map((step) => ({ key: key(), step: { ...step } }));
  return { ...script, parts };
}

// empty parts are left out, like "missing parts are skipped"
function fromDraft(d: Draft): DemoScript {
  const parts: DemoScript["parts"] = {};
  for (const p of DEMO_PARTS) if (d.parts[p].length) parts[p] = d.parts[p].map((r) => r.step);
  return {
    version: 1,
    destination: d.destination,
    reception: d.reception,
    start: d.start,
    personM: d.personM,
    walkSide: d.walkSide,
    glanceDeg: d.glanceDeg,
    fellBackAfterS: d.fellBackAfterS,
    closeMaxS: d.closeMaxS,
    parts,
  };
}

const PACES: { value: DemoPace; label: string }[] = [
  { value: "normal", label: "Normal" },
  { value: "slow", label: "Slow" },
  { value: "very_slow", label: "Very slow" },
];

export function DemoScriptClient() {
  const [info, setInfo] = useState<Info | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [places, setPlaces] = useState<Place[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"save" | "reset" | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);

  const load = useCallback(async () => {
    try {
      const [i, p] = await Promise.all([api<Info>("/api/demo/script"), api<Place[]>("/api/places")]);
      setInfo(i);
      setDraft(toDraft(i.script));
      setPlaces(p.filter((x) => x.active));
      setError(null);
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not load the demo script");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const script = useMemo(() => (draft ? fromDraft(draft) : null), [draft]);
  const errors: ScriptErrors = useMemo(() => (script ? checkScript(script).errors : {}), [script]);
  const dirty = Boolean(info && script && JSON.stringify(checkScript(info.script).script) !== JSON.stringify(checkScript(script).script));
  const problems = Object.keys(errors).length;

  const save = async () => {
    if (!script || problems) return;
    setBusy("save");
    setError(null);
    setDone(null);
    try {
      const i = await post<Info>("/api/demo/script", { script }, "PUT");
      setInfo(i);
      setDraft(toDraft(i.script));
      setDone("Saved. Start on the Demo page sends it to Otto.");
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not save");
    }
    setBusy(null);
  };

  const reset = async () => {
    setBusy("reset");
    setError(null);
    setDone(null);
    try {
      const i = await api<Info>("/api/demo/script", { method: "DELETE" });
      setInfo(i);
      setDraft(toDraft(i.script));
      setConfirmReset(false);
      setDone("Back to the default script.");
    } catch (e) {
      if (!(e instanceof LoginNeeded)) setError(e instanceof Error ? e.message : "Could not reset");
    }
    setBusy(null);
  };

  const update = (fn: (d: Draft) => Draft) => {
    setDone(null);
    setDraft((d) => (d ? fn(d) : d));
  };
  const setRows = (part: DemoPart, fn: (rows: Row[]) => Row[]) => update((d) => ({ ...d, parts: { ...d.parts, [part]: fn(d.parts[part]) } }));

  const actions = (
    <div className="flex flex-wrap items-center gap-3">
      <Button kind="primary" disabled={!dirty || problems > 0 || busy !== null} onClick={save}>
        <FloppyDisk size={18} aria-hidden /> {busy === "save" ? "Saving" : "Save"}
      </Button>
      {dirty && (
        <Button kind="ghost" disabled={busy !== null} onClick={() => info && (setDraft(toDraft(info.script)), setDone(null))}>
          Undo changes
        </Button>
      )}
      {problems > 0 && (
        <span className="text-sm font-medium text-danger">
          {problems === 1 ? "1 thing to fix" : `${problems} things to fix`} before saving
        </span>
      )}
      {done && !dirty && (
        <span className="flex items-center gap-1.5 text-sm text-ok" role="status">
          <CheckCircle size={18} weight="fill" aria-hidden /> {done}
        </span>
      )}
    </div>
  );

  return (
    <section aria-labelledby="demo-script-title">
      <div className="flex flex-wrap items-end justify-between gap-4 pb-4">
        <div className="min-w-0">
          <h2 id="demo-script-title" className="text-xl font-semibold tracking-tight">
            Demo script
          </h2>
          <p className="mt-1 max-w-prose text-[15px] text-muted">
            What Otto says in demo mode, word for word, and the patient&apos;s lines to learn. Start on the Demo page or in the mode switch sends
            the saved script to Otto. <code className="rounded bg-subtle px-1">{"{ticket}"}</code> and{" "}
            <code className="rounded bg-subtle px-1">{"{place}"}</code> are filled in by Otto.
          </p>
        </div>
        <Link href="/demo" className="inline-flex h-11 items-center gap-1 rounded-xl px-3 text-[15px] font-semibold text-primary hover:bg-primary-soft">
          Demo page <ArrowRight size={16} aria-hidden />
        </Link>
      </div>

      {error && <p className="mb-4 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-[15px] text-danger">{error}</p>}

      {!draft || !info ? (
        <div className="grid gap-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-40 animate-pulse rounded-2xl bg-subtle motion-reduce:animate-none" />
          ))}
        </div>
      ) : (
        <div className="grid max-w-5xl gap-5">
          <p className="rounded-xl bg-subtle px-4 py-3 text-[15px] text-muted" role="status">
            {info.isDefault ? "This is the default script." : `Your script, saved at ${info.updatedAt ? clockTime(info.updatedAt) : "an earlier time"}.`}
            {dirty ? " You have changes that are not saved yet." : ""}
          </p>
          {actions}

          <div className={CARD}>
            <h3 className="text-[17px] font-semibold">Where and when</h3>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <PlacePicker
                id="demo-destination"
                label="Otto walks the patient to"
                value={draft.destination}
                places={places}
                error={errors.destination}
                onChange={(v) => update((d) => ({ ...d, destination: v }))}
              />
              <PlacePicker
                id="demo-reception"
                label="Otto waits for the patient at"
                value={draft.reception}
                places={places}
                error={errors.reception}
                onChange={(v) => update((d) => ({ ...d, reception: v }))}
              />
            </div>
            <div className="mt-4 grid gap-4 sm:grid-cols-2">
              <div>
                <p className="text-[15px] font-semibold">The reception part starts</p>
                <div className="mt-2">
                  <Segmented
                    label="The reception part starts"
                    value={draft.start}
                    onChange={(v) => update((d) => ({ ...d, start: v }))}
                    options={[
                      { value: "person", label: "Person in front" },
                      { value: "next", label: "Only with Next" },
                    ]}
                  />
                </div>
                <p className="mt-1 text-sm text-muted">Next on the Demo page always starts it at once.</p>
              </div>
              <div>
                <label htmlFor="demo-person" className="block text-[15px] font-semibold">
                  A person counts within
                </label>
                <div className="mt-2 flex items-center gap-2">
                  <input
                    id="demo-person"
                    type="number"
                    inputMode="decimal"
                    min={PERSON_M.min}
                    max={PERSON_M.max}
                    step={0.1}
                    disabled={draft.start !== "person"}
                    value={Number.isFinite(draft.personM) ? draft.personM : ""}
                    onChange={(e) => update((d) => ({ ...d, personM: e.target.value === "" ? Number.NaN : Number(e.target.value) }))}
                    className={`${INPUT} tabular disabled:opacity-50 ${errors.personM ? "border-danger" : ""}`}
                    aria-invalid={Boolean(errors.personM)}
                  />
                  <span className="w-8 shrink-0 text-sm text-muted">m</span>
                </div>
                <p className={`mt-1 text-sm ${errors.personM ? "font-medium text-danger" : "text-muted"}`}>
                  {errors.personM ?? `How close the person must stand in front of Otto, ${PERSON_M.min} to ${PERSON_M.max} m.`}
                </p>
              </div>
            </div>
          </div>

          <div className={CARD}>
            <h3 className="text-[17px] font-semibold">The walk</h3>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <div>
                <p className="text-[15px] font-semibold">Patient walks on</p>
                <div className="mt-2">
                  <Segmented
                    label="Patient walks on"
                    value={draft.walkSide}
                    onChange={(v) => update((d) => ({ ...d, walkSide: v }))}
                    options={[
                      { value: "right", label: "Otto's right" },
                      { value: "left", label: "Otto's left" },
                    ]}
                  />
                </div>
                <p className={`mt-1 text-sm ${errors.walkSide ? "font-medium text-danger" : "text-muted"}`}>
                  {errors.walkSide ?? "Which side the patient walks on in the demo."}
                </p>
              </div>
              <NumberSetting
                id="demo-glance"
                label="Turn at the first check"
                help="How far Otto turns to look at the patient at the first check (0 = no turn)."
                unit="°"
                range={GLANCE_DEG}
                step={5}
                value={draft.glanceDeg}
                error={errors.glanceDeg}
                onChange={(v) => update((d) => ({ ...d, glanceDeg: v }))}
              />
              <NumberSetting
                id="demo-fellback"
                label="Patient fell back after"
                help="Seconds of walking after the first check until Otto waits for the patient."
                unit="s"
                range={FELL_BACK_AFTER_S}
                step={0.5}
                value={draft.fellBackAfterS}
                error={errors.fellBackAfterS}
                onChange={(v) => update((d) => ({ ...d, fellBackAfterS: v }))}
              />
              <NumberSetting
                id="demo-closemax"
                label="Wait for the patient at most"
                help="Longest wait for the patient to catch up."
                unit="s"
                range={CLOSE_MAX_S}
                step={0.5}
                value={draft.closeMaxS}
                error={errors.closeMaxS}
                onChange={(v) => update((d) => ({ ...d, closeMaxS: v }))}
              />
            </div>
          </div>

          {DEMO_PARTS.map((part) => (
            <PartCard
              key={part}
              part={part}
              rows={draft.parts[part]}
              errors={errors}
              destination={draft.destination}
              closeMaxS={draft.closeMaxS}
              setRows={(fn) => setRows(part, fn)}
            />
          ))}

          <div className={`${CARD} flex flex-wrap items-center justify-between gap-3`}>
            {actions}
            {confirmReset ? (
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium">Replace your script with the default?</span>
                <Button kind="danger" size="sm" disabled={busy !== null} onClick={reset}>
                  {busy === "reset" ? "Resetting" : "Yes, reset"}
                </Button>
                <Button kind="ghost" size="sm" onClick={() => setConfirmReset(false)}>
                  Keep mine
                </Button>
              </div>
            ) : (
              <Button kind="ghost" size="sm" disabled={busy !== null || (info.isDefault && !dirty)} onClick={() => setConfirmReset(true)}>
                <ArrowCounterClockwise size={16} aria-hidden /> Reset to default
              </Button>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

function PlacePicker({
  id,
  label,
  value,
  places,
  error,
  onChange,
}: {
  id: string;
  label: string;
  value: string;
  places: Place[];
  error?: string;
  onChange: (value: string) => void;
}) {
  // matched like otto does: the place's name first, then its spot ("Reception Point" is the reception's spot)
  const match = resolvePlace(places, value);
  const selected = match ? match.name : value;
  return (
    <div className="min-w-0">
      <label htmlFor={id} className="block text-[15px] font-semibold">
        {label}
      </label>
      <select id={id} className={`${INPUT} mt-2 ${error ? "border-danger" : ""}`} value={selected} onChange={(e) => onChange(e.target.value)} aria-invalid={Boolean(error)}>
        {!value && <option value="">Pick a place</option>}
        {value && !match && <option value={value}>{value} (not on the Places page)</option>}
        {places.map((p) => (
          <option key={p.id} value={p.name}>
            {p.name}
            {p.room ? `, Room ${p.room}` : ""}
          </option>
        ))}
      </select>
      <p className={`mt-1 text-sm ${error ? "font-medium text-danger" : match ? "text-muted" : "text-warn"}`}>
        {error ??
          (match
            ? match.name === value
              ? `Spot on Otto's map: ${match.spot}`
              : `"${value}" is the spot of ${match.name}`
            : "Not a place on the Places page. Otto looks for it on its map")}
      </p>
    </div>
  );
}

// a number of the script with its range, unit and one line of help
function NumberSetting({
  id,
  label,
  help,
  unit,
  range,
  step,
  value,
  error,
  onChange,
}: {
  id: string;
  label: string;
  help: string;
  unit: string;
  range: { min: number; max: number };
  step: number;
  value: number;
  error?: string;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <label htmlFor={id} className="block text-[15px] font-semibold">
        {label}
      </label>
      <div className="mt-2 flex items-center gap-2">
        <input
          id={id}
          type="number"
          inputMode="decimal"
          min={range.min}
          max={range.max}
          step={step}
          value={Number.isFinite(value) ? value : ""}
          onChange={(e) => onChange(e.target.value === "" ? Number.NaN : Number(e.target.value))}
          className={`${INPUT} tabular ${error ? "border-danger" : ""}`}
          aria-invalid={Boolean(error)}
        />
        <span className="w-8 shrink-0 text-sm text-muted">{unit}</span>
      </div>
      <p className={`mt-1 text-sm ${error ? "font-medium text-danger" : "text-muted"}`}>
        {error ?? `${help} ${range.min} to ${range.max}${unit === "°" ? "°" : ` ${unit}`}.`}
      </p>
    </div>
  );
}

function PartCard({
  part,
  rows,
  errors,
  destination,
  closeMaxS,
  setRows,
}: {
  part: DemoPart;
  rows: Row[];
  errors: ScriptErrors;
  destination: string;
  closeMaxS: number;
  setRows: (fn: (rows: Row[]) => Row[]) => void;
}) {
  const add = (step: DemoStep) => setRows((r) => [...r, { key: key(), step }]);
  const full = rows.length >= MAX_STEPS;
  return (
    <section className={CARD} aria-labelledby={`part-${part}`}>
      <h3 id={`part-${part}`} className="text-[17px] font-semibold">
        {PART_INFO[part].title}
      </h3>
      <p className="mt-0.5 text-sm text-muted">
        {PART_INFO[part].help}
        {rows.length ? " Empty times: Otto's pause between lines, and 1.2 s + 0.4 s per word for the patient. Next ends a wait." : " No steps: this part is skipped."}
      </p>
      {errors[`parts.${part}`] && <p className="mt-1 text-sm font-medium text-danger">{errors[`parts.${part}`]}</p>}
      {rows.length > 0 && (
        <ol className="mt-3 grid gap-3">
          {rows.map((row, i) => (
            <StepRow
              key={row.key}
              index={i}
              count={rows.length}
              part={part}
              step={row.step}
              errors={errors}
              destination={destination}
              closeMaxS={closeMaxS}
              onChange={(step) => setRows((r) => r.map((x) => (x.key === row.key ? { ...x, step } : x)))}
              onMove={(dir) =>
                setRows((r) => {
                  const next = [...r];
                  const j = i + dir;
                  if (j < 0 || j >= next.length) return r;
                  [next[i], next[j]] = [next[j], next[i]];
                  return next;
                })
              }
              onRemove={() => setRows((r) => r.filter((x) => x.key !== row.key))}
            />
          ))}
        </ol>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <Button size="sm" disabled={full} onClick={() => add({ who: "otto", text: "" })}>
          <Plus size={16} aria-hidden /> Otto line
        </Button>
        <Button size="sm" disabled={full} onClick={() => add({ who: "patient", text: "" })}>
          <Plus size={16} aria-hidden /> Patient line
        </Button>
        <Button size="sm" disabled={full} onClick={() => add({ who: "action", do: "check_in" })}>
          <Plus size={16} aria-hidden /> Action
        </Button>
      </div>
    </section>
  );
}

function StepRow({
  index,
  count,
  part,
  step,
  errors,
  destination,
  closeMaxS,
  onChange,
  onMove,
  onRemove,
}: {
  index: number;
  count: number;
  part: DemoPart;
  step: DemoStep;
  errors: ScriptErrors;
  destination: string;
  closeMaxS: number;
  onChange: (step: DemoStep) => void;
  onMove: (dir: number) => void;
  onRemove: () => void;
}) {
  const at = `parts.${part}.${index}`;
  const id = `demo-${part}-${index}`;
  const textError = errors[`${at}.text`];
  const waitError = errors[`${at}.waitS`];
  const changeWho = (who: string) => {
    if (who === step.who) return;
    if (who === "action") onChange({ who: "action", do: "check_in" });
    else onChange({ who: who as "otto" | "patient", text: step.who === "action" ? "" : step.text, ...(step.who !== "action" && step.waitS !== undefined ? { waitS: step.waitS } : {}) });
  };
  return (
    <li className="rounded-xl border border-line p-3">
      {/* one row: the number, who it is, move and remove */}
      <div className="flex items-center gap-2">
        <span className="w-5 shrink-0 text-sm font-semibold text-muted tabular">{index + 1}</span>
        <label htmlFor={`${id}-who`} className="sr-only">
          Step {index + 1}: who
        </label>
        <select id={`${id}-who`} className={`${SELECT} min-w-0 flex-1 sm:max-w-48`} value={step.who} onChange={(e) => changeWho(e.target.value)}>
          <option value="otto">Otto says</option>
          <option value="patient">Patient says</option>
          <option value="action">Action</option>
        </select>
        <span className="ml-auto flex shrink-0 gap-0.5">
          <Button kind="ghost" size="sm" className="w-9 px-0" aria-label={`Move step ${index + 1} up`} disabled={index === 0} onClick={() => onMove(-1)}>
            <ArrowUp size={16} aria-hidden />
          </Button>
          <Button kind="ghost" size="sm" className="w-9 px-0" aria-label={`Move step ${index + 1} down`} disabled={index === count - 1} onClick={() => onMove(1)}>
            <ArrowDown size={16} aria-hidden />
          </Button>
          <Button kind="quiet" size="sm" className="w-9 px-0" aria-label={`Remove step ${index + 1}`} onClick={onRemove}>
            <Trash size={16} aria-hidden />
          </Button>
        </span>
      </div>
      {step.who === "action" ? (
        <div className="mt-2 grid gap-2 sm:grid-cols-[192px_minmax(0,1fr)] sm:items-center">
          <label htmlFor={`${id}-do`} className="sr-only">
            Step {index + 1}: action
          </label>
          <select
            id={`${id}-do`}
            className={`${SELECT} w-full`}
            value={step.do}
            onChange={(e) => {
              const v = e.target.value;
              onChange(v === "pace" ? { who: "action", do: "pace", value: "slow" } : { who: "action", do: v as "check_in" | "wait_close" });
            }}
          >
            <option value="check_in">Check-in</option>
            <option value="pace">Pace</option>
            <option value="wait_close">Wait until close</option>
          </select>
          {step.do === "pace" ? (
            <div className="max-w-sm">
              <Segmented label={`Step ${index + 1}: pace`} value={step.value} onChange={(v) => onChange({ who: "action", do: "pace", value: v })} options={PACES} />
            </div>
          ) : (
            <p className="text-sm text-muted">
              {step.do === "check_in"
                ? `A real self check-in to ${destination || "the destination"}: the ticket shows on Otto's screen and the team gets the message.`
                : `Otto waits until the patient is close again, at most ${Number.isFinite(closeMaxS) ? closeMaxS : CLOSE_MAX_S.default} s, or Next.`}
            </p>
          )}
        </div>
      ) : (
        <div className="mt-2">
          <label htmlFor={`${id}-text`} className="sr-only">
            Step {index + 1}: {step.who === "otto" ? "what Otto says" : "what the patient says"}
          </label>
          <textarea
            id={`${id}-text`}
            rows={2}
            maxLength={MAX_LINE + 20}
            value={step.text}
            onChange={(e) => onChange({ ...step, text: e.target.value })}
            placeholder={step.who === "otto" ? "What Otto says" : "What the patient says"}
            className={`w-full min-w-0 rounded-xl border bg-surface px-4 py-3 text-base placeholder:text-muted focus:border-primary focus:outline-none ${
              textError ? "border-danger" : "border-line-strong"
            }`}
            aria-invalid={Boolean(textError)}
          />
          {textError && <p className="text-sm font-medium text-danger">{textError}</p>}
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <label htmlFor={`${id}-wait`} className="text-sm font-semibold">
              {step.who === "otto" ? "Pause after" : "Time for the line"}
            </label>
            <input
              id={`${id}-wait`}
              type="number"
              inputMode="decimal"
              min={0}
              max={MAX_WAIT_S}
              step={0.1}
              value={step.waitS ?? ""}
              placeholder={step.who === "otto" ? "auto" : `auto ${patientWaitS({ text: step.text })}`}
              onChange={(e) => {
                const { waitS: _w, ...rest } = step;
                void _w;
                onChange(e.target.value === "" ? rest : { ...rest, waitS: Number(e.target.value) });
              }}
              className={`${SELECT} w-28 tabular ${waitError ? "border-danger" : ""}`}
              aria-invalid={Boolean(waitError)}
            />
            <span className="text-sm text-muted">s</span>
            {waitError && <span className="text-sm font-medium text-danger">{waitError}</span>}
          </div>
        </div>
      )}
    </li>
  );
}
