// Backend vocabulary is fixed: paymentMode is cash|card|upi|other. Only the display label is
// region-aware (Saudi Arabia, region SA, has no UPI; the wireframe reads "Mada & digital" there).
export const PAYMENT_METHODS = ["cash", "card", "upi", "other"] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export function isKsa(region: string | undefined | null): boolean {
  return region === "SA";
}

export function paymentMethodLabel(method: string, region?: string | null): string {
  switch (method) {
    case "cash": return "Cash";
    case "card": return "Card";
    case "upi": return isKsa(region) ? "Mada & digital" : "UPI";
    case "other": return "Other";
    default: return method;
  }
}
