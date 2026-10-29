// DOC-035. Mirrors GET/POST/PATCH/DELETE /v1/clinical/rx-sets (clinical/dto/FavouriteRxSet*.java).
export type RxSetScope = "mine" | "shared" | "standard";
export type RxSetItem = {
  drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null;
  brandName: string | null; sigPattern: string | null; route: string | null; quantity: number | null; quantityUnit: string | null;
  /** true = not in the active pharmacy item master; null = no master to compare with (unknown, never "fine") */
  stale: boolean | null;
  /** CLN-021: drug master code; codeSuggestion = best match for an uncoded item ("code this"), never applied automatically */
  drugId?: string | null; genericName?: string | null; codeSuggestion?: { drugId: string; display: string } | null;
};
export type RxSet = {
  id: string; doctorId: string | null; ownerName: string | null; name: string; scope: RxSetScope; shared: boolean;
  createdAt: string; updatedAt: string; lastUsedAt: string | null;
  staleCheckAvailable: boolean; staleItemCount: number; canEdit: boolean; items: RxSetItem[];
};
/** The structured pad fields of a prescription line (same names and types as PrescriptionItem). */
export type RxSetWriteItem = {
  drugName: string; dosage: string; frequency: string; duration: string; instructions: string;
  brandName?: string | null; sigPattern?: string | null; route?: string | null; quantity?: number | null; quantityUnit?: string | null;
  drugId?: string;
};
export type RxSetResult = { ok: true } | { ok: false; message: string };

export const SCOPE_LABEL: Record<RxSetScope, string> = { mine: "Mine", shared: "Shared with clinic", standard: "Clinic standard" };

const blank = (s: string | null | undefined) => (s && s.trim() ? s.trim() : null);

/** What a set may carry: the structured pad fields only. Never warnings, overrides or decisions: a set is a template, and
 * applying it re-runs the safety checks on the server like any hand-typed line. */
type LooseItem = {
  drugName: string; dosage?: string | null; frequency?: string | null; duration?: string | null; instructions?: string | null;
  brandName?: string | null; sigPattern?: string | null; route?: string | null; quantity?: number | null; quantityUnit?: string | null;
  drugId?: string | null;
};
export function toWriteItem(i: LooseItem): RxSetWriteItem {
  return {
    drugName: i.drugName.trim(), dosage: blank(i.dosage) ?? "", frequency: blank(i.frequency) ?? "", duration: blank(i.duration) ?? "",
    instructions: blank(i.instructions) ?? "", brandName: blank(i.brandName), sigPattern: blank(i.sigPattern), route: blank(i.route),
    quantity: i.quantity ?? null, quantityUnit: blank(i.quantityUnit),
    ...(blank(i.drugId) ? { drugId: blank(i.drugId) as string } : {}), // CLN-021: a coded line stays coded in the set
  };
}

export function medCountLabel(n: number): string {
  return `${n} ${n === 1 ? "med" : "meds"}`;
}

export function lastUsedLabel(iso: string | null): string {
  if (!iso) return "Never used";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "Never used" : `Last used ${d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" })}`;
}

export function itemLine(i: RxSetItem): string {
  return i.brandName ? `${i.drugName} (${i.brandName})` : i.drugName;
}
