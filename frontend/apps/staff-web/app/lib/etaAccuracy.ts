// ACC-019: pure helpers for the owner "ETA accuracy" tile (GET /v1/reports/eta-accuracy rows -> one figure per clinic day).
export type EtaItem = { clinicDate: string; doctorId: string; n: number; maeMin: number; p80HitRate: number; within10Pct?: number; lowConfidence?: boolean };
export type EtaDay = { date: string; n: number; pct: number | null; low: boolean };

/** A clinic day under this many visits is low-confidence (same threshold as the server). */
export const MIN_VISITS = 30;

/** Visit-weighted share of visits called within ±10 min of the shown time, per clinic day, oldest first. */
export function dailyShare(items: EtaItem[]): EtaDay[] {
  const byDay = new Map<string, EtaItem[]>();
  for (const it of items) byDay.set(it.clinicDate, [...(byDay.get(it.clinicDate) ?? []), it]);
  return [...byDay.keys()].sort().map((date) => {
    const rows = byDay.get(date)!;
    const n = rows.reduce((s, r) => s + r.n, 0);
    const known = rows.every((r) => typeof r.within10Pct === "number");
    const pct = n > 0 && known ? Math.round((rows.reduce((s, r) => s + r.n * (r.within10Pct as number), 0) / n) * 100) : null;
    return { date, n, pct, low: n < MIN_VISITS || rows.some((r) => r.lowConfidence === true) };
  });
}

/** SVG polyline points for a 0-100 % series; days without a figure leave a gap (they are skipped, not drawn as 0). */
export function sparkline(days: EtaDay[], width: number, height: number): string {
  const step = days.length > 1 ? width / (days.length - 1) : 0;
  return days
    .map((d, i) => (d.pct === null ? null : `${(i * step).toFixed(1)},${(height - (d.pct / 100) * height).toFixed(1)}`))
    .filter((p): p is string => p !== null)
    .join(" ");
}
