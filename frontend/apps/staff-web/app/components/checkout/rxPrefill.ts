// PHL-018: pure rules of the checkout Rx block (unit-tested). The server decides what is in-house (GET /billing/checkout/{id}/prefill),
// what is left to dispense and what is on the shelf; this only turns that into ticks, bill lines and the shortfall reason.

export type PrefillLine = { chargeCode: string; chargeName: string; qty: number; prescriptionItemId: string; onHand: number };

/** True when the shelf does not cover the quantity: the receptionist must confirm "dispense anyway" with a reason. */
export const isShort = (l: PrefillLine): boolean => l.onHand < l.qty;

/** Rows that start ticked: in-house lines the shelf can cover. A short line stays unticked until a reason is given. */
export const initialTicks = (lines: PrefillLine[]): string[] => lines.filter((l) => !isShort(l)).map((l) => l.chargeCode);

export const REASON_MIN = 3;
export const reasonOk = (text: string): boolean => text.trim().length >= REASON_MIN;

/** The bill line of a ticked row (price and tax come from the visit's charge list, the server re-prices it anyway). */
export function toBillLine(l: PrefillLine, charge: { baseAmount: number; taxRatePercent: number; category: string }, shortfallReason?: string) {
  return {
    chargeCode: l.chargeCode, chargeName: l.chargeName, category: charge.category, quantity: l.qty, unitPrice: charge.baseAmount,
    taxRatePercent: charge.taxRatePercent, prescriptionItemId: l.prescriptionItemId, ...(shortfallReason ? { shortfallReason: shortfallReason.trim() } : {}),
  };
}
