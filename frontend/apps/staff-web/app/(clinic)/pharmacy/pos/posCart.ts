// PHL-019: the counter sale's cart and what is sent to POST /billing/otc-checkout. Pure (unit-tested).
// The server prices every line, picks the batches first-expiry-first and checks the prescription rule again: nothing here is trusted.
import type { FefoPickEntry, StockBatch } from "@nabd/api-client/generated";
import { filled, tenderError, tenderTotalMinor, toMinor, type Tender } from "../../../components/checkout/tender.ts";
import type { PosItem } from "./posIndex.ts";

export type CartLine = {
  item: PosItem; qty: number;
  /** PHL-001: the signed in-system prescription line an Rx-only item is sold against. */
  prescriptionItemId?: string; rxLabel?: string;
  /** PHL-020 slot: the paper / WhatsApp-photo prescription record. Nothing in this story sets it. */
  paperRxId?: string;
};

/** One more of the item (a new line, or +1). Never above the stock on hand; `added` is false when nothing changed. */
export function addItem(cart: CartLine[], item: PosItem): { cart: CartLine[]; added: boolean } {
  const cur = cart.find((l) => l.item.code === item.code);
  if ((cur?.qty ?? 0) >= item.onHand) return { cart, added: false };
  return { cart: cur ? cart.map((l) => (l === cur ? { ...l, qty: l.qty + 1 } : l)) : [...cart, { item, qty: 1 }], added: true };
}

/** Sets a line's quantity: 0 or less removes it, more than the stock on hand is capped. */
export function setQty(cart: CartLine[], code: string, qty: number): CartLine[] {
  if (qty <= 0) return cart.filter((l) => l.item.code !== code);
  return cart.map((l) => (l.item.code === code ? { ...l, qty: Math.min(Math.floor(qty), l.item.onHand) } : l));
}

/** An Rx-only line that has no prescription yet: the sale cannot be sent (the server would answer 422 rx-required). */
export const needsRx = (l: CartLine): boolean => l.item.isRx && !l.prescriptionItemId && !l.paperRxId;

/** The tender rows settle the bill exactly: every amount valid and their sum equal to the total. */
export const tenderSettles = (rows: Tender[], totalMinor: number): boolean =>
  totalMinor > 0 && tenderError(rows, totalMinor) === null && tenderTotalMinor(rows) === totalMinor;

/** `tenders` omitted = a bill without payments (a role that cannot record payments). */
export function saleBody(cart: CartLine[], tenders?: Tender[]) {
  return {
    lineItems: cart.map((l) => ({
      chargeCode: l.item.code, quantity: l.qty,
      ...(l.prescriptionItemId ? { prescriptionItemId: l.prescriptionItemId } : {}),
      ...(l.paperRxId ? { paperRxId: l.paperRxId } : {}),
    })),
    ...(tenders ? { payments: filled(tenders).map((t) => ({ method: t.method, amount: (toMinor(t.amount) ?? 0) / 100 })) } : {}),
  };
}

/** "B123 · exp 03/2027 ×5" for each batch the server would pick; batches without details (a Hybrid clinic's shelf) give nothing. */
export function batchLabels(picks: readonly FefoPickEntry[], batches: readonly StockBatch[]): string[] {
  const out: string[] = [];
  for (const p of picks) {
    const b = batches.find((x) => x.batchId === p.batchId);
    if (!b?.batchNo || !p.expiryDate) continue;
    const [y, m] = p.expiryDate.split("-");
    out.push(`${b.batchNo} · exp ${m}/${y}${picks.length > 1 ? ` ×${p.qty}` : ""}`);
  }
  return out;
}
