// ACC-018: how waiting time is shown to patients. Pure helpers for the schedule panel (the server's PatientEtaView is the real shaper).
export type EtaMode = "exact" | "range" | "position" | "delay_only" | "hidden";

export const ETA_MODES: { value: EtaMode; label: string; example: string }[] = [
  { value: "exact", label: "Exact time", example: "Your consult starts at 10:42" },
  { value: "range", label: "Time range (default)", example: "Expected 10:35-10:55" },
  { value: "position", label: "Position in line only", example: "You are 3rd in line" },
  { value: "delay_only", label: "Delay notices only", example: "No time; only “the doctor is running late”" },
  { value: "hidden", label: "Hidden", example: "Booking time only, no leave-home alert" },
];

export const modeLabel = (m: string | null | undefined): string => ETA_MODES.find((x) => x.value === m)?.label ?? "Time range (default)";

export const EXACT_WARNING = "Exact times will often be wrong; range is safer";
/** Exact mode is risky below this share of visits called within ±10 min of the time shown (ACC-019 measure). */
export const EXACT_MIN_ACCURACY = 0.85;

export type AccuracyRow = { doctorId: string; n: number; within10Pct?: number };

/** Visit-weighted within-10-minutes share of one doctor, or null when there is no measured visit. */
export function doctorAccuracy(rows: AccuracyRow[], doctorId: string): number | null {
  const mine = rows.filter((r) => r.doctorId === doctorId && r.n > 0 && typeof r.within10Pct === "number");
  const n = mine.reduce((s, r) => s + r.n, 0);
  return n === 0 ? null : mine.reduce((s, r) => s + r.n * (r.within10Pct as number), 0) / n;
}

/** The owner's warning for exact mode, or null. */
export function accuracyWarning(mode: string | null | undefined, accuracy: number | null): string | null {
  return mode === "exact" && accuracy !== null && accuracy < EXACT_MIN_ACCURACY ? EXACT_WARNING : null;
}

export const SOURCE_LABEL: Record<string, string> = { session: "this session", doctor: "the doctor's setting", facility: "the clinic default", default: "the built-in default" };
