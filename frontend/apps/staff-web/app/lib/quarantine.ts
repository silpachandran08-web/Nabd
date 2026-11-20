// PHL-009: pure rules of the quarantine bin (unit-tested in tests/unit/s25-phl009-quarantine.test.mjs). The server owns the
// truth: it re-checks that every batch is quarantined, the quantities, the supplier and the witness.

export type QuarantinedBatch = {
  batchId: string; itemId: string; itemName: string; batchNo: string; expiryDate?: string | null; qtyOnHand: number;
  purchaseRate?: number | null; value: number; supplierId?: string | null; controlled: boolean;
};
export type MoveLine = { batchId: string; qty: number };

export const QUARANTINE_REASONS = [
  { id: "expired", label: "Expired" },
  { id: "damaged", label: "Damaged" },
  { id: "other", label: "Other (note required)" },
] as const;
export type QuarantineReason = (typeof QUARANTINE_REASONS)[number]["id"];

export const WRITE_OFF_METHODS = [
  { id: "destroyed", label: "Destroyed" },
  { id: "damaged", label: "Damaged" },
  { id: "lost", label: "Lost" },
] as const;
export type WriteOffMethod = (typeof WRITE_OFF_METHODS)[number]["id"];

/** A whole number from 1 to the batch's quantity, else null. Blank means the whole batch. */
export function parseQty(text: string | undefined, onHand: number): number | null {
  const t = (text ?? "").trim();
  if (t === "") return onHand;
  if (!/^\d{1,8}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= onHand ? n : null;
}

/** The request lines for the ticked batches, or the first problem a person can fix. */
export function moveLines(selected: readonly QuarantinedBatch[], qty: Readonly<Record<string, string>>): { lines: MoveLine[]; problem: string | null } {
  if (selected.length === 0) return { lines: [], problem: "Tick at least one batch." };
  const lines: MoveLine[] = [];
  for (const b of selected) {
    const n = parseQty(qty[b.batchId], b.qtyOnHand);
    if (n == null) return { lines: [], problem: `Enter a quantity from 1 to ${b.qtyOnHand} for ${b.itemName} (${b.batchNo}).` };
    lines.push({ batchId: b.batchId, qty: n });
  }
  return { lines, problem: null };
}

/** Value of the lines at purchase rate (a batch without a rate counts 0), to 2 places. */
export function linesValue(selected: readonly QuarantinedBatch[], lines: readonly MoveLine[]): number {
  const cents = lines.reduce((sum, l) => sum + Math.round((selected.find((b) => b.batchId === l.batchId)?.purchaseRate ?? 0) * l.qty * 100), 0);
  return cents / 100;
}

/** One return note per supplier: batches received from different known suppliers cannot share a note. */
export function supplierProblem(selected: readonly QuarantinedBatch[]): string | null {
  const known = new Set(selected.map((b) => b.supplierId).filter(Boolean));
  return known.size > 1 ? "These batches came from different suppliers. Make one return note per supplier." : null;
}

/** The supplier id the note carries: the one the batches were received from, when recorded. */
export function supplierIdOf(selected: readonly QuarantinedBatch[]): string | undefined {
  return selected.find((b) => b.supplierId)?.supplierId ?? undefined;
}

/** Writing off a controlled drug (a class with a statutory register) needs the second staff member. */
export const needsWitness = (selected: readonly QuarantinedBatch[]): boolean => selected.some((b) => b.controlled);
