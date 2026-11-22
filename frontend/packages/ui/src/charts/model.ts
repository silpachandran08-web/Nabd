// FEF-026 chart kit: pure helpers (no React, no DOM). Erasable TypeScript only, so Node can import this file as it is in unit tests.

/** One series. `values[i]` belongs to `categories[i]`; null (or a missing index) is a gap, never a zero. */
export type ChartSeries = { id: string; label: string; values: (number | null)[] };
export type ChartKind = "column" | "bar" | "line" | "area";
/** What the data itself says; `Chart` adds loading / error / noPermission / stale / partial from its props. */
export type DataState = "empty" | "singlePoint" | "allZero" | "negative" | "ok";

/** The palette has six validated slots (packages/tokens/chart-palette.mjs); a seventh colour is never generated. */
export const MAX_SERIES = 6;

declare const process: { env: { NODE_ENV?: string } } | undefined;
const devWarn = (message: string) => { if (typeof process === "undefined" || process.env.NODE_ENV !== "production") console.warn(message); };

const total = (s: ChartSeries) => s.values.reduce<number>((a, v) => a + Math.abs(v ?? 0), 0);

/**
 * Series-count guard: with more than six series the five largest (by absolute total) keep their place and colour, the rest
 * are summed into one "Other" series, and a warning is logged outside production.
 * Summing is right for counts and money; pass at most six series yourself for rates or averages.
 */
export function limitSeries(series: ChartSeries[], otherLabel = "Other", warn: (message: string) => void = devWarn): { shown: ChartSeries[]; grouped: number } {
  if (series.length <= MAX_SERIES) return { shown: series, grouped: 0 };
  const keep = new Set([...series].sort((a, b) => total(b) - total(a)).slice(0, MAX_SERIES - 1).map((s) => s.id));
  const rest = series.filter((s) => !keep.has(s.id));
  const length = Math.max(...series.map((s) => s.values.length));
  const values = Array.from({ length }, (_, i) => {
    const present = rest.map((s) => s.values[i]).filter((v): v is number => v != null);
    return present.length ? present.reduce((a, v) => a + v, 0) : null;
  });
  warn(`[nabd/ui charts] ${series.length} series passed; a chart shows at most ${MAX_SERIES}. The ${rest.length} smallest are grouped into "${otherLabel}".`);
  return { shown: [...series.filter((s) => keep.has(s.id)), { id: "__other", label: otherLabel, values }], grouped: rest.length };
}

export function dataState(categories: string[], series: ChartSeries[]): DataState {
  const values = series.flatMap((s) => s.values.slice(0, categories.length)).filter((v): v is number => v != null && Number.isFinite(v));
  if (!categories.length || !values.length) return "empty";
  if (values.every((v) => v === 0)) return "allZero";
  if (categories.length === 1) return "singlePoint";
  return values.some((v) => v < 0) ? "negative" : "ok";
}

const fix = (v: number) => Number(v.toPrecision(12));

/** Round axis: bounds and ticks on 1 / 2 / 2.5 / 5 steps. Zero is always inside unless `fit` (line charts of a narrow clinical range). */
export function niceScale(min: number, max: number, target = 4, fit = false): { min: number; max: number; ticks: number[] } {
  let lo = fit ? min : Math.min(0, min), hi = fit ? max : Math.max(0, max);
  if (lo === hi) {
    if (lo === 0) { hi = 1; target = 1; } else { lo -= Math.abs(lo) * 0.1; hi += Math.abs(hi) * 0.1; }
  }
  const raw = (hi - lo) / target, mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw * (1 - 1e-9)) ?? 10 * mag;
  const from = Math.floor(fix(lo / step)), to = Math.ceil(fix(hi / step));
  const ticks = Array.from({ length: to - from + 1 }, (_, i) => fix((from + i) * step));
  return { min: ticks[0], max: ticks[ticks.length - 1], ticks };
}

/** The table view: one row per category, one column per series (every series, also those the chart grouped into "Other"). */
export function chartTable(categoryLabel: string, categories: string[], series: ChartSeries[], format: (n: number) => string = String): { head: string[]; rows: string[][] } {
  return {
    head: [categoryLabel, ...series.map((s) => s.label)],
    rows: categories.map((c, i) => [c, ...series.map((s) => { const v = s.values[i]; return v == null ? "" : format(v); })]),
  };
}

// A text cell that a spreadsheet would run as a formula is prefixed with an apostrophe; numbers are written raw so they stay numbers.
function csvCell(v: string | number | null | undefined): string {
  if (v == null) return "";
  if (typeof v === "number") return String(v);
  const s = /^[=+\-@\t\r]/.test(v) ? `'${v}` : v;
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** RFC 4180 CSV of the table view, with unformatted numbers. */
export function toCsv(categoryLabel: string, categories: string[], series: ChartSeries[]): string {
  const rows: (string | number | null | undefined)[][] = [[categoryLabel, ...series.map((s) => s.label)], ...categories.map((c, i) => [c, ...series.map((s) => s.values[i])])];
  return rows.map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Bar from the baseline to the value: 4px rounded data end, square at the baseline. `across` = horizontal bar. */
export function barPath(across: boolean, pos: number, thickness: number, base: number, value: number): string {
  const r = Math.min(4, thickness / 2, Math.abs(value - base)), s = value < base ? 1 : -1, end = pos + thickness;
  return across
    ? `M${base},${pos}H${value + s * r}Q${value},${pos} ${value},${pos + r}V${end - r}Q${value},${end} ${value + s * r},${end}H${base}Z`
    : `M${pos},${base}V${value + s * r}Q${pos},${value} ${pos + r},${value}H${end - r}Q${end},${value} ${end},${value + s * r}V${base}Z`;
}

/** Runs of consecutive non-null points; a gap in the data breaks the line instead of being drawn as zero. */
export function segments<P>(points: (P | null)[]): P[][] {
  const out: P[][] = [];
  let run: P[] = [];
  for (const p of points) { if (p == null) { if (run.length) out.push(run); run = []; } else run.push(p); }
  if (run.length) out.push(run);
  return out;
}
