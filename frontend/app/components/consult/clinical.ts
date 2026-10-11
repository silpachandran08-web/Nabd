"use client";

import { useCallback, useEffect, useState } from "react";
import type { Allergy, AuthedFetch, Condition, Encounter, Prescription, Vitals } from "./shared";

// One load of the patient's clinical context for a visit: allergy register, conditions, last
// encounters, prescriptions and this visit's vitals. Every part reports its own state so a failed
// call is shown as "couldn't load", never as an empty (and therefore reassuring) list.
export type Part<T> = { status: "loading" | "ok" | "none" | "error"; data: T | null };
export type ClinicalContext = {
  key: string;
  allergies: Part<Allergy[]>;
  conditions: Part<Condition[]>;
  timeline: Part<Encounter[]>;
  prescriptions: Part<Prescription[]>;
  vitals: Part<Vitals>;
};

const loading = <T,>(): Part<T> => ({ status: "loading", data: null });
const blank = (key: string): ClinicalContext => ({
  key, allergies: loading(), conditions: loading(), timeline: loading(), prescriptions: loading(), vitals: loading(),
});

async function part<T>(authedFetch: AuthedFetch, path: string, pick: (j: unknown) => T): Promise<Part<T>> {
  try {
    const res = await authedFetch(path);
    if (!res) return { status: "error", data: null };
    if (res.status === 404) return { status: "none", data: null };
    if (!res.ok) return { status: "error", data: null };
    return { status: "ok", data: pick(await res.json()) };
  } catch {
    return { status: "error", data: null };
  }
}

export function useClinicalContext(authedFetch: AuthedFetch, patientId: string | null, entryId: string | null) {
  const key = patientId && entryId ? `${patientId}:${entryId}` : "";
  const [ctx, setCtx] = useState<ClinicalContext>(() => blank(""));
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!patientId || !entryId) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (cancelled) return;
      setCtx(blank(key));
      const [allergies, conditions, timeline, prescriptions, vitals] = await Promise.all([
        part<Allergy[]>(authedFetch, `/clinical/patients/${patientId}/allergies`, (j) => j as Allergy[]),
        part<Condition[]>(authedFetch, `/clinical/patients/${patientId}/conditions`, (j) => j as Condition[]),
        part<Encounter[]>(authedFetch, `/clinical/patients/${patientId}/timeline?limit=3`, (j) => (j as { data: Encounter[] }).data ?? []),
        part<Prescription[]>(authedFetch, `/clinical/patients/${patientId}/prescriptions`, (j) => j as Prescription[]),
        part<Vitals>(authedFetch, `/clinical/vitals/${entryId}`, (j) => j as Vitals),
      ]);
      if (!cancelled) setCtx({ key, allergies, conditions, timeline, prescriptions, vitals });
    });
    return () => { cancelled = true; };
  }, [authedFetch, patientId, entryId, key, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ctx: ctx.key === key ? ctx : blank(key), reload };
}
