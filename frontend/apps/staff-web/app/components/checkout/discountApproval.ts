// FIN-018: pure rules for the checkout's awaiting-approval banner and the owner's approvals inbox.
import type { DiscountApproval, LeakageItem } from "@nabd/api-client/generated";

export type { DiscountApproval, LeakageItem };

/** The request the desk banner shows for this bill: the newest one, unless it was issued or ran out. */
export function bannerApproval(items: readonly DiscountApproval[]): DiscountApproval | null {
  const a = items[0];
  return a && (a.billStatus === "awaiting_approval" || a.billStatus === "approved" || a.billStatus === "rejected") ? a : null;
}

/** A 409 from checkout that means "ask the owner first". */
export const needsApproval = (problem: unknown): boolean =>
  !!problem && typeof problem === "object" && (problem as { code?: unknown }).code === "APPROVAL_REQUIRED";

/** Minor units -> major, exact for 2-decimal currencies (INR, SAR, AED, QAR). */
export const fromMinor = (minor: number): number => minor / 100;

/** Major -> minor units, or null when not a non-negative number with at most 2 decimals. */
export function toMinorUnits(v: string): number | null {
  const t = v.trim();
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(t)) return null;
  const [w, f = ""] = t.split(".");
  return Number(w) * 100 + Number((f + "00").slice(0, 2));
}

/** A threshold policy value ('off', minor units, or a percent) as the owner types it. */
export function thresholdInput(key: "amount" | "percent", stored: string | undefined): string {
  if (!stored || stored === "off") return "";
  return key === "amount" ? String(fromMinor(Number(stored))) : stored;
}

/** What the owner typed -> the policy value to store; "" turns the threshold off; null = invalid. */
export function thresholdValue(key: "amount" | "percent", typed: string): string | null {
  const t = typed.trim();
  if (t === "") return "off";
  if (key === "amount") { const m = toMinorUnits(t); return m === null ? null : String(m); }
  return /^(100(\.0{1,2})?|\d{1,2}(\.\d{1,2})?)$/.test(t) ? t : null;
}

export const LEAKAGE_TYPE_LABEL: Record<string, string> = {
  discount: "Discount", override: "Below-list price", write_off: "Write-off", refund: "Refund",
};

export const APPROVAL_METHOD_LABEL: Record<string, string> = {
  direct: "Applied directly", in_app: "Owner (app)", pin: "Owner (PIN)", maker_checker: "Second person",
};
