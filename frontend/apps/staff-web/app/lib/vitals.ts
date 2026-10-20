// Vitals validation shared by /nursing (Capture vitals) and /arrivals (Record vitals).
// Vitals validation. HARD limits are physiologically impossible values (almost certainly a typo
// such as temp 370) and block saving; SOFT ranges are the usual adult range and only raise a
// non-blocking "check value" chip while typing. The HARD numbers mirror the API's plausibility bounds
// (VitalsWriteRequest, deliberately wide at the low end so genuine neonatal/paediatric, shock and
// hypothermic readings can be saved; the server answers 400 outside them). The backend's own
// abnormal flags (shown after save, age-banded) remain the authority; these client numbers are a typing aid, never a clinical decision.
export type VitalKey = "heightCm" | "weightKg" | "bpSystolic" | "bpDiastolic" | "pulseBpm" | "tempCelsius" | "spo2Percent";
export const VITAL_FIELDS: { key: VitalKey; label: string; unit: string; min: number; max: number; soft?: [number, number]; step: string }[] = [
  { key: "heightCm", label: "Height", unit: "cm", min: 20, max: 250, step: "0.1" },
  { key: "weightKg", label: "Weight", unit: "kg", min: 0.2, max: 400, step: "0.1" },
  { key: "bpSystolic", label: "BP systolic", unit: "mmHg", min: 20, max: 300, soft: [90, 140], step: "1" },
  { key: "bpDiastolic", label: "BP diastolic", unit: "mmHg", min: 10, max: 200, soft: [60, 90], step: "1" },
  { key: "pulseBpm", label: "Pulse", unit: "bpm", min: 20, max: 300, soft: [60, 100], step: "1" },
  { key: "tempCelsius", label: "Temp", unit: "°C", min: 20, max: 45, soft: [36, 37.8], step: "0.1" },
  { key: "spo2Percent", label: "SpO2", unit: "%", min: 1, max: 100, soft: [95, 100], step: "1" },
];
export type VitalsForm = Record<VitalKey, string>;
export const EMPTY_VITALS: VitalsForm = { heightCm: "", weightKg: "", bpSystolic: "", bpDiastolic: "", pulseBpm: "", tempCelsius: "", spo2Percent: "" };

export function vitalsErrors(f: VitalsForm): Partial<Record<VitalKey | "form", string>> {
  const errs: Partial<Record<VitalKey | "form", string>> = {};
  let any = false;
  for (const d of VITAL_FIELDS) {
    const raw = f[d.key].trim();
    if (raw === "") continue;
    any = true;
    const n = Number(raw);
    if (!Number.isFinite(n)) errs[d.key] = "Enter a number.";
    else if (d.step === "1" && !Number.isInteger(n)) errs[d.key] = "Enter a whole number.";
    else if (n < d.min || n > d.max) errs[d.key] = `Must be ${d.min}–${d.max} ${d.unit}. Check the value.`;
  }
  if (!any) errs.form = "Enter at least one measurement before saving.";
  const hasSys = f.bpSystolic.trim() !== "";
  const hasDia = f.bpDiastolic.trim() !== "";
  if (hasSys !== hasDia) errs[hasSys ? "bpDiastolic" : "bpSystolic"] = "Enter both blood-pressure values.";
  else if (hasSys && !errs.bpSystolic && !errs.bpDiastolic && Number(f.bpSystolic) <= Number(f.bpDiastolic)) {
    errs.bpSystolic = "Systolic must be higher than diastolic.";
  }
  return errs;
}


/** Blank boxes go out as null; the server keeps the stored value for null (COALESCE), it never clears it. */
export const vitalsPayload = (f: VitalsForm) =>
  Object.fromEntries(VITAL_FIELDS.map((d) => [d.key, f[d.key].trim() === "" ? null : Number(f[d.key])]));

export const VITALS_BLANK_HINT = "Leave a box blank to keep its saved value. A saved value can't be cleared here, only replaced by the correct one.";

// ---- NUR-021/022: nurse capture form extras (blood sugar, pain score, one combined BP box). ----
// Blood-sugar unit is a display constant, not stored: NEEDS CLINICAL SIGN-OFF (mg/dL vs mmol/L).
export const BLOOD_SUGAR_UNIT = "mg/dL";
export const PAIN_SCORE_MAX = 10;
export type VitalsExtras = { bloodSugar: string; painScore: string };
export const EMPTY_EXTRAS: VitalsExtras = { bloodSugar: "", painScore: "" };

/** "120/80" or "120 / 80" -> strings for the two BP fields. Blank is fine (both ""); junk sets error. */
export function parseBp(text: string): { sys: string; dia: string; error?: string } {
  const t = text.trim();
  if (t === "") return { sys: "", dia: "" };
  const m = /^(\d{1,3})\s*\/\s*(\d{1,3})$/.exec(t);
  return m ? { sys: m[1], dia: m[2] } : { sys: "", dia: "", error: "Enter blood pressure as systolic/diastolic, e.g. 120/80." };
}

/** Errors for the nurse form, keyed by input: bp (combined), the other vitals, bloodSugar, painScore, form. */
export function nurseVitalsErrors(f: VitalsForm, bpText: string, x: VitalsExtras): Partial<Record<VitalKey | "bp" | "bloodSugar" | "painScore" | "form", string>> {
  const bp = parseBp(bpText);
  const { bpSystolic, bpDiastolic, form, ...rest } = vitalsErrors({ ...f, bpSystolic: bp.sys, bpDiastolic: bp.dia });
  const errs: ReturnType<typeof nurseVitalsErrors> = { ...rest };
  const bpErr = bp.error ?? bpSystolic ?? bpDiastolic;
  if (bpErr) errs.bp = bpErr;
  const sugar = x.bloodSugar.trim(), pain = x.painScore.trim();
  if (sugar !== "" && !(Number.isFinite(Number(sugar)) && Number(sugar) > 0)) errs.bloodSugar = "Enter a number above 0.";
  if (pain !== "" && !(Number.isInteger(Number(pain)) && Number(pain) >= 0 && Number(pain) <= PAIN_SCORE_MAX)) errs.painScore = `Enter a whole number 0–${PAIN_SCORE_MAX}.`;
  // vitalsErrors only sees the 7 base fields; blood sugar / pain / a typed BP also count as "something entered".
  if (form && sugar === "" && pain === "" && bpText.trim() === "") errs.form = form;
  return errs;
}

export const nurseVitalsPayload = (f: VitalsForm, bpText: string, x: VitalsExtras) => {
  const bp = parseBp(bpText);
  return {
    ...vitalsPayload({ ...f, bpSystolic: bp.sys, bpDiastolic: bp.dia }),
    bloodSugar: x.bloodSugar.trim() === "" ? null : Number(x.bloodSugar),
    painScore: x.painScore.trim() === "" ? null : Number(x.painScore),
  };
};

/** One previous-visit row from GET /clinical/vitals/patient/{id}/previous; every vitals field may be absent. */
export type PreviousVitals = {
  queueEntryId: string; recordedAt?: string | null;
  bpSystolic?: number | null; bpDiastolic?: number | null; pulseBpm?: number | null; tempCelsius?: number | null;
  spo2Percent?: number | null; weightKg?: number | null; heightCm?: number | null; bloodSugar?: number | null; painScore?: number | null;
};
export const formatBp = (v: PreviousVitals) => (v.bpSystolic != null && v.bpDiastolic != null ? `${v.bpSystolic}/${v.bpDiastolic}` : "—");
