// FEF-032: what the Treatment Packages screen's parts share (moved unchanged from the old single page).
import { formatNumber } from "@nabd/i18n";
import styles from "./packages.module.css";
import { ApiError } from "../../lib/api";
import type { Full } from "../../lib/gen";
import type {
  ExpiringSoonResponse, InstanceEventResponse, InstanceItemResponse, InstanceResponse, InvoiceResponse, LiabilityResponse, LineItemResponse,
  PackageItemResponse, PackageResponse, RefundResponse, StaffRosterEntry,
} from "@nabd/api-client/generated";

// GET/POST/PATCH /v1/packages/** (PackageController) — the generated types, with the fields the server always sends marked present.
export type PackageT = Omit<Full<PackageResponse, "speciality" | "description" | "refundNote" | "doctorLeaveWarning">, "items"> & { items: Full<PackageItemResponse>[] };
export type InstanceT = Omit<Full<InstanceResponse, "validityStart" | "validityEnd" | "events">, "items" | "events"> & {
  items: Full<InstanceItemResponse>[]; events: Full<InstanceEventResponse, "note" | "delta">[] | null;
};
export type RefundT = Full<RefundResponse, "creditNoteNumber" | "recomputedAmount">;
export type ExpiringT = Full<ExpiringSoonResponse>;
export type LiabilityT = Full<LiabilityResponse>;
export type StaffOption = Full<StaffRosterEntry>;
export type ItemDraft = { itemType: string; name: string; quantity: string; unitListPrice: string; taxRatePercent: string };
export type Tab = "packages" | "active" | "sales" | "sessions" | "expiring" | "refunds" | "liability" | "settings";
// Invoice issued by POST /packages/sell, read back from GET /billing/invoices/{id} (billing:view) so the
// final step shows the server's own net / tax / total and per-line tax, never a client-side estimate.
export type SoldInvoice = Omit<Full<InvoiceResponse>, "lineItems"> & { lineItems: Full<LineItemResponse>[] };

export const TABS: { key: Tab; label: string }[] = [
  { key: "packages", label: "Packages" },
  { key: "active", label: "Active patient packages" },
  { key: "sales", label: "Sales" },
  { key: "sessions", label: "Sessions & redemptions" },
  { key: "expiring", label: "Expiring soon" },
  { key: "refunds", label: "Refunds & adjustments" },
  { key: "liability", label: "Package liability" },
  { key: "settings", label: "Package settings" },
];
export const STATUS_PILL: Record<string, string> = {
  active: styles.pillActive, grace: styles.pillWarn, expired: styles.pillDanger,
  completed: styles.pillDone, refunded: styles.pillInactive, cancelled: styles.pillInactive,
  on_sale: styles.pillActive, draft: styles.pillWarn, inactive: styles.pillInactive,
  pending: styles.pillWarn, approved: styles.pillDone, rejected: styles.pillInactive,
};
// Sell-package wizard (wireframe pksell SELLSTEPS).
export const SELL_STEPS = ["Patient", "Package", "Eligibility", "Review", "Payment", "Invoice & card"];
// POST /packages/sell refuses (duplicate-active-package) while the stored status is 'active'. The list shows an
// effective status computed by date (PackageInstanceService.effectiveStatus), so active, grace and expired copies
// are all still stored as 'active' and all block a new sale; only completed or refunded copies do not.
export const BLOCKING_STATUSES = ["active", "grace", "expired"];
export const EMPTY_ITEM: ItemDraft = { itemType: "service_session", name: "", quantity: "1", unitListPrice: "0", taxRatePercent: "0" };

export function money(n: number): string {
  return formatNumber(n, { min: 2, max: 2 });
}

/** The words a person sees for a refused request: the server's reason, or the offline line. */
export const why = (e: unknown, fallback: string) =>
  e instanceof ApiError ? e.detail || fallback : "Could not reach the server. Check your connection and try again.";
