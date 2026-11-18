// PHL-008: stock alert tabs, labels and the acknowledge choices (pure: unit-tested in tests/unit/s24-phl008-stock-alerts.test.mjs).

export type AlertTab = "expiry" | "low" | "dead";
export type AlertCounts = { expiry: number; low: number; dead: number };
export type AckAction = "returned" | "discounted" | "quarantined" | "none";

export const TABS: { id: AlertTab; label: string }[] = [
  { id: "expiry", label: "Expiry" },
  { id: "low", label: "Low stock" },
  { id: "dead", label: "Dead stock" },
];

export const TYPE_LABEL: Record<string, string> = {
  expired: "Expired",
  expiry_30: "Within 30 days",
  expiry_60: "Within 60 days",
  expiry_90: "Within 90 days",
  low_stock: "Below reorder level",
  dead_stock: "No sales in 90 days",
};

export const ACTIONS: { id: AckAction; label: string }[] = [
  { id: "returned", label: "Returned to supplier" },
  { id: "discounted", label: "Front shelf / discounted" },
  { id: "quarantined", label: "Quarantined" },
  { id: "none", label: "No action needed" },
];

export const SUGGESTED_LABEL: Record<string, string> = {
  quarantine: "Quarantine",
  discount: "Front shelf / discount",
  return_to_supplier: "Return to supplier",
};

export const WINDOWS = [30, 60, 90] as const;

/** The nav badge: every open alert across the three tabs; undefined (no badge) when unknown or zero. */
export function badgeCount(counts: Partial<AlertCounts> | null | undefined): number | undefined {
  if (!counts) return undefined;
  const n = (counts.expiry ?? 0) + (counts.low ?? 0) + (counts.dead ?? 0);
  return n > 0 ? n : undefined;
}

/** "Expired 5 days ago", "Today", "In 12 days". */
export function daysText(days: number | null | undefined): string {
  if (days == null) return "";
  if (days < 0) return `Expired ${-days} ${-days === 1 ? "day" : "days"} ago`;
  if (days === 0) return "Expires today";
  return `In ${days} ${days === 1 ? "day" : "days"}`;
}

/** Selection after a bulk acknowledge: drops the ids that went through, keeps the ones that failed for a retry. */
export function remaining(selected: readonly string[], done: readonly string[]): string[] {
  const ok = new Set(done);
  return selected.filter((id) => !ok.has(id));
}
