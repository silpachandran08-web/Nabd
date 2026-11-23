// PHL-042: a lab test's reference ranges: shapes and the grid's rules (pure: unit-tested in tests/unit/s27-phl042-lab-ranges.test.mjs).
// The server (LabRangeEvaluator) applies the same band rules on save; these give the answer before the request.

export type Sex = "M" | "F" | "any";
export const SEXES: Sex[] = ["any", "F", "M"];
/** An age band includes ageMinDays and ends just before ageMaxDays (null = no upper age). Limits are in the test's unit. */
export type RangeBand = {
  sex: Sex; ageMinDays: number; ageMaxDays?: number | null; low?: number | null; high?: number | null;
  criticalLow?: number | null; criticalHigh?: number | null; text?: string | null;
};
/** version 0 with no ranges: the test has none yet. */
export type RangeSet = { version: number; effectiveFrom?: string | null; ranges: RangeBand[] };

export type AgeUnit = "d" | "m" | "y";
export const AGE_UNITS: AgeUnit[] = ["d", "m", "y"];
// shortcut: average month and year lengths, so a band typed in years can sit a day off a real birthday; the server stores days,
// so type days where a day matters (neonates).
const DAYS: Record<AgeUnit, number> = { d: 1, m: 30.4375, y: 365.25 };
export const MAX_AGE_DAYS = 55000;
export const toDays = (n: number, unit: AgeUnit): number => Math.round(n * DAYS[unit]);
/** Days as the largest unit that gives them back exactly: 4383 -> 12 y, 61 -> 2 m, 28 -> 28 d. */
export function ageParts(days: number): { n: number; unit: AgeUnit } {
  for (const unit of ["y", "m"] as const) {
    const n = Math.round(days / DAYS[unit]);
    if (n > 0 && toDays(n, unit) === days) return { n, unit };
  }
  return { n: days, unit: "d" };
}

/** One grid row: what the person typed. toN blank = no upper age. */
export type RangeRow = {
  sex: Sex; fromN: string; fromUnit: AgeUnit; toN: string; toUnit: AgeUnit;
  low: string; high: string; criticalLow: string; criticalHigh: string; text: string;
};
export const emptyRow = (): RangeRow => ({ sex: "any", fromN: "0", fromUnit: "y", toN: "", toUnit: "y", low: "", high: "", criticalLow: "", criticalHigh: "", text: "" });
const str = (v: number | null | undefined): string => (v == null ? "" : String(v));
/** The grid for a stored set; a test without ranges starts with one blank band. */
export const rowsOf = (s: RangeSet): RangeRow[] => (s.ranges.length ? s.ranges.map(rowOf) : [emptyRow()]);
export function rowOf(b: RangeBand): RangeRow {
  const from = ageParts(b.ageMinDays);
  const to = b.ageMaxDays == null ? null : ageParts(b.ageMaxDays);
  return {
    sex: b.sex, fromN: String(from.n), fromUnit: from.unit, toN: to ? String(to.n) : "", toUnit: to ? to.unit : from.unit,
    low: str(b.low), high: str(b.high), criticalLow: str(b.criticalLow), criticalHigh: str(b.criticalHigh), text: b.text ?? "",
  };
}

const WHOLE = /^\d{1,5}$/;
const LIMIT = /^-?\d{1,9}(\.\d{1,6})?$/;
const LIMITS = ["criticalLow", "low", "high", "criticalHigh"] as const;
const num = (v: string): number | undefined => (v.trim() ? Number(v) : undefined);

/** The band a row stands for; what is blank is left out of the request. Only call it on rows rangesProblem accepts. */
export function bandOf(r: RangeRow): RangeBand {
  return {
    sex: r.sex, ageMinDays: toDays(Number(r.fromN), r.fromUnit), ageMaxDays: r.toN.trim() ? toDays(Number(r.toN), r.toUnit) : undefined,
    low: num(r.low), high: num(r.high), criticalLow: num(r.criticalLow), criticalHigh: num(r.criticalHigh), text: r.text.trim() || undefined,
  };
}

export type RangeError = "none" | "age" | "ageOrder" | "number" | "order" | "empty" | "overlap" | "invalid";
/** row and other are positions from 1, as the screen numbers the bands. */
export type RangeProblem = { error: RangeError; row: number; other?: number };

function rowError(r: RangeRow): RangeError | null {
  if (!WHOLE.test(r.fromN.trim()) || (r.toN.trim() && !WHOLE.test(r.toN.trim()))) return "age";
  const b = bandOf(r);
  if (b.ageMinDays > MAX_AGE_DAYS || (b.ageMaxDays != null && b.ageMaxDays > MAX_AGE_DAYS)) return "age";
  if (b.ageMaxDays != null && b.ageMaxDays <= b.ageMinDays) return "ageOrder";
  if (LIMITS.some((k) => r[k].trim() && !LIMIT.test(r[k].trim()))) return "number";
  let last = -1;
  for (let i = 0; i < LIMITS.length; i++) {
    const v = b[LIMITS[i]];
    if (v == null) continue;
    // only low and high may be equal
    if (last >= 0 && ((b[LIMITS[last]] as number) > v || ((b[LIMITS[last]] as number) === v && !(last === 1 && i === 2)))) return "order";
    last = i;
  }
  return last < 0 && !b.text ? "empty" : null;
}

/** Two bands a patient could fall into at once: the same or 'any' sex and ages that meet. */
export function bandsOverlap(a: RangeBand, b: RangeBand): boolean {
  const sex = a.sex === "any" || b.sex === "any" || a.sex === b.sex;
  return sex && (b.ageMaxDays == null || a.ageMinDays < b.ageMaxDays) && (a.ageMaxDays == null || b.ageMinDays < a.ageMaxDays);
}

/** The first thing that stops a save, or null. An empty grid cannot be saved. */
export function rangesProblem(rows: readonly RangeRow[]): RangeProblem | null {
  if (rows.length === 0) return { error: "none", row: 0 };
  for (let i = 0; i < rows.length; i++) {
    const error = rowError(rows[i]);
    if (error) return { error, row: i + 1 };
  }
  const bands = rows.map(bandOf);
  for (let i = 0; i < bands.length; i++) {
    for (let j = i + 1; j < bands.length; j++) if (bandsOverlap(bands[i], bands[j])) return { error: "overlap", row: i + 1, other: j + 1 };
  }
  return null;
}

/** The server's 422 as the same kind of problem, so the screen shows its own sentence: overlapping-bands names two bands,
 * invalid-band one (positions from 0). Anything else is null. */
export function serverProblem(status: number, problem: Record<string, unknown>): RangeProblem | null {
  if (status !== 422) return null;
  const pair = problem.bands;
  if (problem.code === "OVERLAPPING_BANDS" && Array.isArray(pair) && pair.length === 2) return { error: "overlap", row: Number(pair[0]) + 1, other: Number(pair[1]) + 1 };
  if (problem.code === "INVALID_BAND" && typeof problem.band === "number") return { error: "invalid", row: problem.band + 1 };
  return null;
}
