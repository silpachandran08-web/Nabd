"use client";
// FEF-040: payment-method names in the user's language. The backend vocabulary stays cash|card|upi|other; only the label is
// translated (and region-aware like @nabd/domain paymentMethodLabel: Saudi Arabia shows "Mada & digital" for upi).
import { useTranslations } from "next-intl";
import { isKsa } from "@nabd/domain";

export function usePaymentMethodLabel(): (method: string, region?: string | null) => string {
  const t = useTranslations("common.paymentMethod");
  return (method, region) => (method === "upi" ? t(isKsa(region) ? "mada" : "upi") : ["cash", "card", "other"].includes(method) ? t(method) : method);
}
