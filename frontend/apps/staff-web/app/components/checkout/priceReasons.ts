// FIN-003: coded reasons for a price override or discount. Same codes as the server's BillingPricing.REASON_CODES
// (billing-local until the shared T-321 list lands).
export const PRICE_REASONS: { code: string; label: string }[] = [
  { code: "goodwill", label: "Goodwill" },
  { code: "staff", label: "Staff" },
  { code: "price_match", label: "Price match" },
  { code: "waiver", label: "Waiver" },
  { code: "pricing_error", label: "Pricing error" },
  { code: "other", label: "Other" },
];

export function reasonLabel(code: string | null | undefined): string {
  return PRICE_REASONS.find((r) => r.code === code)?.label ?? (code ?? "");
}
