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
