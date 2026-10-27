// FIN-015/FIN-016: the checkout tier choices for a Consultation line and the owner's follow-up window rule. Pure, no React.
export type TierName = "normal" | "follow_up" | "emergency";

/** One entry of GET /billing/checkout/{id} suggestedLines (the server's pick plus the prices on offer). */
export type TierLine = {
  chargeCode: string; name: string; tier: TierName; unitPrice: number; reason?: string | null;
  normalAmount?: number | null; followUpAmount?: number | null; emergencyAmount?: number | null;
};

export type TierOption = { tier: TierName; label: string; amount: number };

const LABEL: Record<TierName, string> = { normal: "Normal", follow_up: "Follow-up", emergency: "Emergency" };

/** Only the tiers the server offers: normal always, follow-up when the patient is eligible, emergency when the head has a price. */
export function tierOptions(l: TierLine): TierOption[] {
  const out: TierOption[] = [{ tier: "normal", label: LABEL.normal, amount: l.normalAmount ?? (l.tier === "normal" ? l.unitPrice : 0) }];
  if (l.followUpAmount != null) out.push({ tier: "follow_up", label: LABEL.follow_up, amount: l.followUpAmount });
  if (l.emergencyAmount != null) out.push({ tier: "emergency", label: LABEL.emergency, amount: l.emergencyAmount });
  return out;
}

export function tierPrice(l: TierLine, tier: TierName): number {
  return tierOptions(l).find((o) => o.tier === tier)?.amount ?? l.unitPrice;
}

/** The chip under the line: why this tier. The server's reason belongs to the tier it suggested; a manual pick says so. */
export function tierChip(l: TierLine, picked: TierName): string {
  if (picked === l.tier && l.reason) return l.reason;
  return picked === "emergency" ? "Emergency price (picked)" : picked === "follow_up" ? "Follow-up price (picked)" : "Normal price (picked)";
}

/** The tier to send with the line: only an emergency suggestion (confirmed by checking out) or an explicit pick; otherwise the server decides. */
export function tierToSend(l: TierLine, picked: TierName | undefined): TierName | undefined {
  if (picked) return picked;
  return l.tier === "emergency" ? "emergency" : undefined;
}

/** Owner setting: a whole number of days, 1 to 90. Returns the message to show, or null when valid. */
export function windowError(raw: string): string | null {
  if (!/^\d{1,3}$/.test(raw.trim())) return "Enter a whole number of days.";
  const n = Number(raw);
  return n < 1 || n > 90 ? "Use a number of days from 1 to 90." : null;
}
