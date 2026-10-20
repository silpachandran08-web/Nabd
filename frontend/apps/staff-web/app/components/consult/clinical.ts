"use client";

import { useCallback, useEffect, useState } from "react";
import type { Allergy, AllergyStatus, AuthedFetch, PregnancyStatus, Condition, Encounter, Prescription, Vitals } from "./shared";

// One load of the patient's clinical context for a visit: allergy register, conditions, last
// encounters, prescriptions and this visit's vitals. Every part reports its own state so a failed
// call is shown as "couldn't load", never as an empty (and therefore reassuring) list.
export type Part<T> = { status: "loading" | "ok" | "none" | "error"; data: T | null };
export type ClinicalContext = {
  key: string;
  allergies: Part<Allergy[]>;
  allergyStatus: Part<AllergyStatus>;
  pregnancyStatus: Part<PregnancyStatus>;
  conditions: Part<Condition[]>;
  timeline: Part<Encounter[]>;
  prescriptions: Part<Prescription[]>;
  vitals: Part<Vitals>;
};

const loading = <T,>(): Part<T> => ({ status: "loading", data: null });
const blank = (key: string): ClinicalContext => ({
  key, allergies: loading(), allergyStatus: loading(), pregnancyStatus: loading(), conditions: loading(), timeline: loading(), prescriptions: loading(), vitals: loading(),
});

/** IDC-001: the server's 404 for a gated read (consent withdrawn / outside the caller's scope), as opposed to "nothing recorded yet". */
export async function isPatientNotFound(res: Response): Promise<boolean> {
  if (res.status !== 404) return false;
  const body = await res.clone().json().catch(() => null) as { type?: unknown } | null;
  return typeof body?.type === "string" && body.type.endsWith("/patient-not-found");
}

async function part<T>(authedFetch: AuthedFetch, path: string, pick: (j: unknown) => T): Promise<Part<T>> {
  try {
    const res = await authedFetch(path);
    if (!res) return { status: "error", data: null };
    if (await isPatientNotFound(res)) return { status: "error", data: null }; // never shown as an empty, reassuring list
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
      const [allergies, allergyStatus, pregnancyStatus, conditions, timeline, prescriptions, vitals] = await Promise.all([
        part<Allergy[]>(authedFetch, `/clinical/patients/${patientId}/allergies`, (j) => j as Allergy[]),
        part<AllergyStatus>(authedFetch, `/clinical/patients/${patientId}/allergies/status`, (j) => j as AllergyStatus),
        part<PregnancyStatus>(authedFetch, `/clinical/patients/${patientId}/pregnancy-status`, (j) => j as PregnancyStatus),
        part<Condition[]>(authedFetch, `/clinical/patients/${patientId}/conditions`, (j) => j as Condition[]),
        part<Encounter[]>(authedFetch, `/clinical/patients/${patientId}/timeline?limit=3`, (j) => (j as { data: Encounter[] }).data ?? []),
        part<Prescription[]>(authedFetch, `/clinical/patients/${patientId}/prescriptions`, (j) => j as Prescription[]),
        part<Vitals>(authedFetch, `/clinical/vitals/${entryId}`, (j) => j as Vitals),
      ]);
      if (!cancelled) setCtx({ key, allergies, allergyStatus, pregnancyStatus, conditions, timeline, prescriptions, vitals });
    });
    return () => { cancelled = true; };
  }, [authedFetch, patientId, entryId, key, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { ctx: ctx.key === key ? ctx : blank(key), reload };
}
