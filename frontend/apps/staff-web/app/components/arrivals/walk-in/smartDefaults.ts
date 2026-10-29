// FEF-034: smart defaults for the walk-in dialog, pure. Doctor: the desk's last choice, else the shortest wait from the wait
// estimates the board has already loaded (fewer waiting breaks a tie, then roster order), else the only doctor. Payment: the desk's
// last choice, else the mode most used on today's queue, else cash.
type Doc = { id: string };
type Wait = { id: string; waiting: number; estimatedMinutes?: number };

export function defaultDoctor(staff: Doc[], queues: Wait[], last: string): string {
  if (last && staff.some((s) => s.id === last)) return last;
  const waits = staff.map((s) => queues.find((q) => q.id === s.id)).filter((q): q is Wait => !!q && typeof q.estimatedMinutes === "number");
  if (waits.length > 0) return waits.reduce((a, b) => (b.estimatedMinutes! < a.estimatedMinutes! || (b.estimatedMinutes === a.estimatedMinutes && b.waiting < a.waiting) ? b : a)).id;
  return staff.length === 1 ? staff[0].id : "";
}

export function defaultPayment(rows: { paymentMode?: string | null }[], allowed: readonly string[], last: string, fallback = "cash"): string {
  if (allowed.includes(last)) return last;
  const n = new Map<string, number>();
  for (const r of rows) if (r.paymentMode && allowed.includes(r.paymentMode)) n.set(r.paymentMode, (n.get(r.paymentMode) ?? 0) + 1);
  let best = fallback, top = 0;
  for (const [m, c] of n) if (c > top) { best = m; top = c; }
  return best;
}
