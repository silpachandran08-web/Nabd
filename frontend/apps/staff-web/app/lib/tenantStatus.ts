// ADM-020: what staff-web shows when Nabd moved the clinic's lifecycle state (GET /v1/me/entitlements tenantStatus).
// Pure rules, unit-tested; TenantStatusBanner only renders what these return.
import type { Entitlements } from "./entitlementRules";

export type Money = { amountMinor: number; currency: string };
export type NabdInvoice = Money & { status: string };

/** Where the owner settles a Nabd invoice: the plan page lists the invoices (the payment gateway itself is outside Core). */
export const PAY_HREF = "/plan";

/** suspended: everyone signed in sees it (the clinic is read-only). overdue: the owner alone, and nothing is blocked. */
export function statusBanner(ent: Entitlements | null, owner: boolean): "suspended" | "overdue" | null {
  if (ent?.tenantStatus === "suspended") return "suspended";
  if (ent?.tenantStatus === "overdue" && owner) return "overdue";
  return null;
}

/**
 * The amount the owner owes: the unpaid Nabd invoices in the plan's currency, else the renewal amount that was missed.
 * Null when neither is known: the banner then states the fact without a made-up figure.
 */
export function amountDue(invoices: NabdInvoice[] | null | undefined, renewal: Money | null | undefined): Money | null {
  const due = (invoices ?? []).filter((i) => i.status === "due" && i.amountMinor > 0);
  if (due.length > 0) {
    const currency = renewal?.currency ?? due[0].currency;
    const same = due.filter((i) => i.currency === currency);
    if (same.length > 0) return { amountMinor: same.reduce((sum, i) => sum + i.amountMinor, 0), currency };
  }
  return renewal && renewal.amountMinor > 0 ? { amountMinor: renewal.amountMinor, currency: renewal.currency } : null;
}
