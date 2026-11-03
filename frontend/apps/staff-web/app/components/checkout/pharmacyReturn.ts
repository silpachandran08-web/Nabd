// PHL-011: pure rules of the counter return overlay (GET /pharmacy/returns/bill, POST /pharmacy/returns).
import type { PharmacyReturnRequest, RefundRequestResponse, ReturnableBill, ReturnableBillLine } from "@nabd/api-client/generated";

export type { RefundRequestResponse, ReturnableBill, ReturnableBillLine };

/** What the pharmacist ticked on one line: the quantity as typed and whether the pack was opened. */
export type Pick = { qty: string; opened: boolean };

export const OPENED_REASON = "Opened pack: only sealed packs can be taken back.";

/** Why a line cannot be ticked (the server's reason, or an opened pack), or null. */
export function lineBlock(l: ReturnableBillLine, opened: boolean): string | null {
  if (!l.allowed) return l.reason ?? "This line can't be returned.";
  return opened ? OPENED_REASON : null;
}

/** A whole number from 1 to max, else null. */
export function parseQty(v: string, max: number): number | null {
  const t = v.trim();
  if (!/^\d{1,6}$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= max ? n : null;
}

/** The POST body for the ticked lines, or the problem to show instead. refundMethod "" = back to the bill's payments in order. */
export function returnBody(bill: ReturnableBill, picks: Record<string, Pick>, refundMethod = ""): { body: PharmacyReturnRequest } | { problem: string } {
  const lines: PharmacyReturnRequest["lines"] = [];
  for (const l of bill.lines) {
    const p = picks[l.invoiceLineId];
    if (!p) continue;
    const block = lineBlock(l, p.opened);
    if (block) return { problem: `${l.name}: ${block}` };
    const qty = parseQty(p.qty, l.returnableQty);
    if (qty == null) return { problem: `${l.name}: enter a whole number from 1 to ${l.returnableQty}.` };
    lines.push({ invoiceLineId: l.invoiceLineId, qty, condition: "sealed" });
  }
  if (lines.length === 0) return { problem: "Tick at least one line to return." };
  return { body: { invoiceId: bill.invoiceId, lines, ...(refundMethod ? { refundMethod } : {}) } };
}

/** The message for a refused return (the server's detail names the line's reason). */
export function returnErrorMessage(problem: unknown, fallback: string): string {
  const p = (problem && typeof problem === "object" ? problem : {}) as { code?: unknown; detail?: unknown };
  if (p.code === "APPROVAL_PENDING") return "A return or refund of this bill is already waiting for approval.";
  return typeof p.detail === "string" && p.detail ? p.detail : fallback;
}

/** The approver's error for a decision (maker-checker: whoever raised the return cannot approve it). */
export function decisionErrorMessage(problem: unknown, fallback: string): string {
  const p = (problem && typeof problem === "object" ? problem : {}) as { code?: unknown; detail?: unknown };
  if (p.code === "SAME_USER") return "You raised this return. Someone else must approve it.";
  if (p.code === "THRESHOLD_REQUIRES_OWNER") return "This refund is above the owner threshold: the owner must approve it.";
  return typeof p.detail === "string" && p.detail ? p.detail : fallback;
}

/** Counter returns among the pending refund requests (FIN-013 reason code customer_return). */
export const pendingReturns = (items: readonly RefundRequestResponse[]): RefundRequestResponse[] =>
  items.filter((r) => r.reasonCode === "customer_return" && r.status === "pending");
