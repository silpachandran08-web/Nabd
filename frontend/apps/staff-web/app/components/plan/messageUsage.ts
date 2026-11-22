// COM-015: the owner's message usage model (GET /v1/owner/messaging/usage?month=YYYY-MM). Pure: unit-tested directly.
export type UsageItem = { channel: string; category: string; count: number; costMinor: number; currency: string };
export type UsageAlert = { thresholdPct: number; at: string };
/** allowance is absent when the plan has none: cost is shown without a bar. */
export type MessageUsage = { month: string; channels: UsageItem[]; allowance?: { limit: number; used: number } | null; alerts: UsageAlert[] };

/** Messages and cost per currency. Meta bills in the account's currency and the SMS provider in its own: never added across currencies. */
export const totals = (items: UsageItem[]): { currency: string; count: number; costMinor: number }[] => {
  const by = new Map<string, { currency: string; count: number; costMinor: number }>();
  for (const i of items) {
    const t = by.get(i.currency) ?? { currency: i.currency, count: 0, costMinor: 0 };
    t.count += i.count;
    t.costMinor += i.costMinor;
    by.set(i.currency, t);
  }
  return [...by.values()].sort((a, b) => a.currency.localeCompare(b.currency));
};

/** "2026-01" moved by whole months ("2025-12" for -1). */
export const shiftMonth = (month: string, by: number): string => {
  const [y, m] = month.split("-").map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
};

/** The one alert to show: the highest threshold reached this month (100 replaces 80); null when none. */
export const topAlert = (alerts: UsageAlert[]): UsageAlert | null =>
  alerts.reduce<UsageAlert | null>((top, a) => (top === null || a.thresholdPct > top.thresholdPct ? a : top), null);
