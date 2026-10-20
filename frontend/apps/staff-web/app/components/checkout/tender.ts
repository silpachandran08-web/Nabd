// REC-044: split-tender maths in integer minor units so cents never drift. Amounts are never
// rounded or clamped for the cashier: an invalid or over-balance split is refused with a message.
export type Tender = { id: number; method: string; amount: string };

/** Minor units of a typed amount, or null when blank / not a positive number with at most 2 decimals. */
export function toMinor(raw: string): number | null {
  const s = raw.trim();
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const n = Math.round(Number(s) * 100);
  return n > 0 ? n : null;
}

/** Rows that carry an amount (blank rows are ignored, not charged). */
export const filled = (rows: Tender[]) => rows.filter((r) => r.amount.trim() !== "");

export function tenderTotalMinor(rows: Tender[]): number {
  return filled(rows).reduce((sum, r) => sum + (toMinor(r.amount) ?? 0), 0);
}

/** null when the split can be collected against `limitMinor` (the total, or the balance due). */
export function tenderError(rows: Tender[], limitMinor: number): string | null {
  const used = filled(rows);
  if (used.some((r) => toMinor(r.amount) === null)) return "Enter each amount as a positive number with at most 2 decimals.";
  if (tenderTotalMinor(rows) > limitMinor) return "The amounts add up to more than the balance due.";
  return null;
}
