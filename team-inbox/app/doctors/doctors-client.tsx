"use client";

import { useCallback, useEffect, useId, useState } from "react";
import type { Doctor } from "@/lib/types";
import { DEFAULT_CONSULT_MINUTES, MAX_CONSULT_MINUTES, MIN_CONSULT_MINUTES } from "@/lib/types";
import { PAGE, api } from "@/lib/ui";
import { Button } from "../components/button";

// minutes stay text while typing, the api gets a number
type FormValues = { name: string; spot: string; avgConsultMinutes: string; teamsWebhookUrl: string; active: boolean };

const EMPTY: FormValues = {
  name: "",
  spot: "",
  avgConsultMinutes: String(DEFAULT_CONSULT_MINUTES),
  teamsWebhookUrl: "",
  active: true,
};

function toBody(v: Partial<FormValues>, id?: string) {
  const minutes = v.avgConsultMinutes === undefined ? undefined : Number(v.avgConsultMinutes);
  return JSON.stringify({ id, ...v, avgConsultMinutes: minutes });
}

const INPUT = "w-full rounded-lg border border-line-strong bg-surface px-3 py-2 text-base placeholder:text-muted";

export function DoctorsClient({ practiceChannel }: { practiceChannel: boolean }) {
  const [doctors, setDoctors] = useState<Doctor[] | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setDoctors(await api<Doctor[]>("/api/doctors"));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load doctors");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const create = async (v: FormValues) => {
    await api("/api/doctors", { method: "POST", body: toBody(v) });
    await load();
  };

  const update = async (id: string, v: Partial<FormValues>) => {
    await api("/api/doctors", { method: "PATCH", body: toBody(v, id) });
    await load();
  };

  const teamsHint = practiceChannel
    ? "Optional. Leave empty to post in the practice channel."
    : "Optional. No practice channel is set either, so without it this doctor gets no Teams message.";

  return (
    <main className={`${PAGE} pb-16`}>
      <div className="pt-8 pb-6">
        <h1 className="text-2xl font-semibold">Doctors</h1>
        <p className="max-w-2xl text-base text-muted">
          Nova matches what a patient says against these names, then walks the patient to the map spot. Changes count
          right away.
        </p>
      </div>

      {error && (
        <p role="status" className="mb-6 rounded-xl border border-danger bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      )}

      <div className="grid items-start gap-12 lg:grid-cols-[384px_minmax(0,1fr)]">
        <section aria-labelledby="add-doctor">
          <h2 id="add-doctor" className="mb-4 text-lg font-semibold">
            Add a doctor
          </h2>
          <div className="rounded-2xl border border-line bg-surface p-6">
            <DoctorForm initial={EMPTY} submitLabel="Add doctor" teamsHint={teamsHint} onSubmit={create} resetOnSave />
          </div>
        </section>

        <section aria-labelledby="doctor-list">
          <h2 id="doctor-list" className="mb-4 text-lg font-semibold">
            In the practice{doctors ? ` (${doctors.length})` : ""}
          </h2>
          {doctors === null ? (
            <p className="text-base text-muted">Loading</p>
          ) : doctors.length === 0 ? (
            <p className="rounded-2xl border border-dashed border-line-strong p-6 text-base text-muted">
              No doctors yet. Add the first one with the form.
            </p>
          ) : (
            <ul className="rounded-2xl border border-line bg-surface">
              {doctors.map((d, i) => (
                <li key={d.id} className={`p-6 ${i > 0 ? "border-t border-line" : ""}`}>
                  {editing === d.id ? (
                    <DoctorForm
                      initial={{
                        name: d.name,
                        spot: d.spot,
                        avgConsultMinutes: String(d.avgConsultMinutes),
                        teamsWebhookUrl: d.teamsWebhookUrl ?? "",
                        active: d.active,
                      }}
                      submitLabel="Save"
                      teamsHint={teamsHint}
                      onSubmit={async (v) => {
                        await update(d.id, v);
                        setEditing(null);
                      }}
                      onCancel={() => setEditing(null)}
                    />
                  ) : (
                    <DoctorRow
                      doctor={d}
                      practiceChannel={practiceChannel}
                      onEdit={() => setEditing(d.id)}
                      onToggle={() => update(d.id, { active: !d.active })}
                    />
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </main>
  );
}

function DoctorRow({
  doctor,
  practiceChannel,
  onEdit,
  onToggle,
}: {
  doctor: Doctor;
  practiceChannel: boolean;
  onEdit: () => void;
  onToggle: () => void;
}) {
  const teams = doctor.teamsWebhookUrl
    ? "Own Teams channel"
    : practiceChannel
      ? "Practice Teams channel"
      : "No Teams channel";
  return (
    <div className="flex flex-wrap items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="flex flex-wrap items-center gap-3">
          <span className="text-lg font-semibold break-words">{doctor.name}</span>
          <span
            className={`rounded-full px-2 py-1 text-xs font-medium ${
              doctor.active ? "bg-ok-soft text-ok" : "bg-subtle text-muted"
            }`}
          >
            {doctor.active ? "Active" : "Inactive"}
          </span>
        </p>
        <p className="text-sm text-muted break-words">
          Map spot: {doctor.spot}. About {doctor.avgConsultMinutes} min per patient. {teams}.
        </p>
      </div>
      <div className="flex gap-2">
        <Button onClick={onEdit}>Edit</Button>
        <Button onClick={onToggle}>{doctor.active ? "Deactivate" : "Activate"}</Button>
      </div>
    </div>
  );
}

function DoctorForm({
  initial,
  submitLabel,
  teamsHint,
  onSubmit,
  onCancel,
  resetOnSave = false,
}: {
  initial: FormValues;
  submitLabel: string;
  teamsHint: string;
  onSubmit: (v: FormValues) => Promise<void>;
  onCancel?: () => void;
  resetOnSave?: boolean;
}) {
  const id = useId();
  const [values, setValues] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<FormValues>) => setValues((v) => ({ ...v, ...patch }));

  return (
    <form
      className="flex max-w-2xl flex-col gap-4"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(null);
        try {
          await onSubmit(values);
          if (resetOnSave) setValues(initial);
        } catch (err) {
          setError(err instanceof Error ? err.message : "Could not save");
        }
        setBusy(false);
      }}
    >
      <Field id={`${id}-name`} label="Name" hint="As patients say it, for example the family name with title.">
        <input
          id={`${id}-name`}
          className={INPUT}
          required
          value={values.name}
          onChange={(e) => set({ name: e.target.value })}
          autoComplete="off"
        />
      </Field>
      <Field id={`${id}-spot`} label="Map spot" hint="The exact spot name on Nova's map. Empty means same as the name.">
        <input
          id={`${id}-spot`}
          className={INPUT}
          value={values.spot}
          onChange={(e) => set({ spot: e.target.value })}
          autoComplete="off"
        />
      </Field>
      <Field
        id={`${id}-minutes`}
        label="Minutes per patient"
        hint={`About how long one visit takes, ${MIN_CONSULT_MINUTES} to ${MAX_CONSULT_MINUTES}. Nova uses it to tell patients how long they wait.`}
      >
        <input
          id={`${id}-minutes`}
          className={`${INPUT} max-w-32`}
          type="number"
          inputMode="numeric"
          required
          min={MIN_CONSULT_MINUTES}
          max={MAX_CONSULT_MINUTES}
          step={1}
          value={values.avgConsultMinutes}
          onChange={(e) => set({ avgConsultMinutes: e.target.value })}
        />
      </Field>
      <Field id={`${id}-teams`} label="Teams webhook URL" hint={teamsHint}>
        <input
          id={`${id}-teams`}
          className={INPUT}
          type="url"
          value={values.teamsWebhookUrl}
          onChange={(e) => set({ teamsWebhookUrl: e.target.value })}
          placeholder="https://"
          autoComplete="off"
        />
      </Field>
      <div className="flex flex-col gap-1">
        <label className="flex items-center gap-3 text-sm font-medium">
          <input
            type="checkbox"
            className="size-4 accent-[var(--primary)]"
            checked={values.active}
            onChange={(e) => set({ active: e.target.checked })}
          />
          Active
        </label>
        <p className="text-xs text-muted">Nova only checks patients in for active doctors.</p>
      </div>
      {error && <p className="text-sm text-danger">{error}</p>}
      <div className="flex gap-2">
        <Button kind="primary" type="submit" disabled={busy}>
          {submitLabel}
        </Button>
        {onCancel && <Button onClick={onCancel}>Cancel</Button>}
      </div>
    </form>
  );
}

function Field({ id, label, hint, children }: { id: string; label: string; hint: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
      <p className="text-xs text-muted">{hint}</p>
    </div>
  );
}
