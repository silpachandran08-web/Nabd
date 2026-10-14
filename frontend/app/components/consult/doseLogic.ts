// DOC-036 dose calculator helpers. Pure so they can be reasoned about on their own.

/** Shape of GET /clinical/patients/{id}/dose-calculator. The rule is a clinician-supplied mg/kg rate, so the API
 * returns no per-dose or daily maximum and none is ever shown or invented here. */
export type DoseResult = {
  weightKg: number; mgPerKg: number; totalDoseMg: number; ruleSource: string;
  /** When the weight was recorded; null/absent only if the server could not say (treated as unknown, never as fresh). */
  weightRecordedAt?: string | null;
};

export const STALE_WEIGHT_MONTHS = 3;

export type WeightAge = { state: "unknown" } | { state: "fresh" | "stale"; months: number; date: Date };

/** Calendar-month age of a recorded weight. A missing or unparseable timestamp is "unknown": it is never treated as fresh. */
export function weightAge(recordedAt: string | null | undefined, now: Date = new Date()): WeightAge {
  if (!recordedAt) return { state: "unknown" };
  const date = new Date(recordedAt);
  if (Number.isNaN(date.getTime())) return { state: "unknown" };
  let months = (now.getFullYear() - date.getFullYear()) * 12 + (now.getMonth() - date.getMonth());
  if (now.getDate() < date.getDate()) months -= 1;
  months = Math.max(0, months);
  // "More than 3 months old": stale only once now is past recordedAt + 3 calendar months.
  const limit = new Date(date);
  limit.setMonth(limit.getMonth() + STALE_WEIGHT_MONTHS);
  return { state: now.getTime() > limit.getTime() ? "stale" : "fresh", months, date };
}

/** Validates the typed weight against the same plausibility bounds the vitals API enforces (0.2 to 400 kg). */
export function weightError(raw: string): string | null {
  const t = raw.trim();
  if (t === "") return "Enter the measured weight in kg.";
  const n = Number(t);
  if (!Number.isFinite(n)) return "Enter a number.";
  if (n < 0.2 || n > 400) return "Must be 0.2 to 400 kg. Check the value.";
  return null;
}

/** mg/kg must be a positive number before it is sent. */
export function rateError(raw: string): string | null {
  const t = raw.trim();
  if (t === "") return "Enter a mg/kg rate.";
  const n = Number(t);
  if (!Number.isFinite(n) || n <= 0) return "Enter a positive number.";
  return null;
}
