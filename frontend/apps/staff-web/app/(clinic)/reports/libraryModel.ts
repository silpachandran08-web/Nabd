// ADM-047: pure helpers of the reports library screen (no React, no workspace imports), unit-tested in s25-adm047-reports-library.test.mjs.

export type ReportGroup = "money" | "patients" | "team";
export type ChartFormat = "money" | "count";
/** tabOf: the report opens as a tab of that library entry, not as its own entry. defaultDays: the range it opens with (30 when absent). */
export type ReportDef = { key: string; group: ReportGroup; requires?: string; chart: ChartFormat; tabOf?: string; defaultDays?: number };

/** Library order. The keys are the API ids (GET /v1/reports/{key}) and the `report` query value of the screen's URL: other screens link by them. */
export const REPORT_CATALOG: ReportDef[] = [
  { key: "leakage", group: "money", chart: "money" },
  { key: "takings", group: "money", chart: "money" },
  { key: "package-liability", group: "money", requires: "packages:view", chart: "count" },
  // ADM-049: tabs of the package report. Redemption judges sales at least 90 days old, so it opens on the last 12 months.
  { key: "package-sales", group: "money", requires: "packages:view", chart: "count", tabOf: "package-liability" },
  { key: "package-redemption", group: "money", requires: "packages:view", chart: "count", tabOf: "package-liability", defaultDays: 365 },
  { key: "package-expiry-loss", group: "money", requires: "packages:view", chart: "money", tabOf: "package-liability" },
  { key: "tax-summary", group: "money", chart: "money" },
  { key: "patient-sources", group: "patients", chart: "count" },
  { key: "patient-retention", group: "patients", chart: "count" },
  { key: "no-show-watch", group: "patients", chart: "count" },
  { key: "staff-activity", group: "team", chart: "money" },
  { key: "doctor-productivity", group: "team", chart: "money" },
  { key: "doctor-on-time", group: "team", chart: "count" },
];
export const GROUPS: ReportGroup[] = ["money", "patients", "team"];
export const MAX_DAYS = 366;

/** A report the caller may not open is absent from the list (the server repeats the gate). */
export function visibleReports(allowed: (permission: string) => boolean): ReportDef[] {
  return REPORT_CATALOG.filter((r) => !r.requires || allowed(r.requires));
}

/** The tabs a report sits in (its library entry first), or none when it stands alone. */
export function tabsOf(def: ReportDef, catalog: ReportDef[]): ReportDef[] {
  const root = def.tabOf ?? def.key;
  const tabs = catalog.filter((r) => r.key === root || r.tabOf === root);
  return tabs.length > 1 ? tabs : [];
}

export const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;

export type Preset = "d7" | "d30" | "month";

/** The browser's local calendar days (staff browsers run on the clinic's clock), end = today. */
export function presetRange(p: Preset, now: Date): { from: string; to: string } {
  const to = iso(now);
  if (p === "month") return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to };
  const from = new Date(now);
  from.setDate(from.getDate() - (p === "d7" ? 6 : 29));
  return { from: iso(from), to };
}

/** The range a report opens with when its link carries no dates: the last `days` days ending today (30 when the report names none). */
export function defaultRange(days: number | undefined, now: Date): { from: string; to: string } {
  if (!days) return presetRange("d30", now);
  const from = new Date(now);
  from.setDate(from.getDate() - (days - 1));
  return { from: iso(from), to: iso(now) };
}

const DAY = /^\d{4}-\d{2}-\d{2}$/;

/** null when the range is fine, else which rule it breaks (the screen shows it instead of asking the server). */
export function rangeProblem(from: string, to: string): "invalid" | "order" | "long" | null {
  if (!DAY.test(from) || !DAY.test(to)) return "invalid";
  if (to < from) return "order";
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000 + 1;
  return days > MAX_DAYS ? "long" : null;
}

/** The deep link other screens use (for example the owner overview tiles): report key plus the same filters. */
export function reportHref(key: string, f: { from?: string; to?: string; branch?: string } = {}): string {
  const q = new URLSearchParams({ report: key });
  if (f.from) q.set("from", f.from);
  if (f.to) q.set("to", f.to);
  if (f.branch) q.set("branch", f.branch);
  return `/reports?${q}`;
}

export type FrameQuery = { key: string; from: string; to: string; branch?: string | null; cursor?: string | null; threshold?: string | null };

export function framePath(q: FrameQuery): string {
  const p = new URLSearchParams({ from: q.from, to: q.to });
  if (q.branch) p.set("branch", q.branch);
  if (q.cursor) p.set("cursor", q.cursor);
  if (q.threshold) p.set("threshold", q.threshold);
  return `/reports/${q.key}?${p}`;
}

/** Base64 (the export answer) to bytes, as an ArrayBuffer a Blob takes. Decoded by hand: the browser Base64 global is banned by lint (app/lib/jwt.ts owns it). */
export function decodeBase64(b64: string): ArrayBuffer {
  const A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  const clean = b64.replace(/=+$/, "");
  const out = new Uint8Array(Math.floor((clean.length * 3) / 4));
  let bits = 0, acc = 0, n = 0;
  for (const ch of clean) {
    acc = (acc << 6) | A.indexOf(ch);
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[n++] = (acc >> bits) & 0xff;
    }
  }
  return out.buffer as ArrayBuffer;
}

/** "snake_case" codes from the server shown as words. */
export const words = (v: unknown) => String(v ?? "").replace(/_/g, " ");
