// FEF-030: pure helpers of the prescription pad.
import type { Prescription, PrescriptionItem } from "../shared";

export const BLANK_RX_ITEM: PrescriptionItem = { drugName: "", dosage: "", frequency: "", duration: "", instructions: "" };

// The API returns nullable dosage/frequency/duration/instructions (a favourite set commonly omits
// duration) — every input is a controlled component, so null must become "" before it renders.
export function normalizeRxItem(i: PrescriptionItem): PrescriptionItem {
  return { ...i, dosage: i.dosage ?? "", frequency: i.frequency ?? "", duration: i.duration ?? "", instructions: i.instructions ?? "" };
}

/** The pad's lines for a saved prescription: always at least one (blank) line to type into. */
export const padItems = (rx: Prescription): PrescriptionItem[] => (rx.items.length > 0 ? rx.items.map(normalizeRxItem) : [{ ...BLANK_RX_ITEM }]);

export const openWarningCount = (items: PrescriptionItem[]) => items.reduce((n, i) => n + (i.warnings ?? []).filter((w) => w.requiresDecision && !w.resolved).length, 0);
