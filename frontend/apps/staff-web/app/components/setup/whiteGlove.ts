// ADM-006: the wizard's white-glove booking model (GET/POST /v1/setup/white-glove). Pure: unit-tested directly.
export type Fee = { amountMinor: number; currency: string; status: "due" | "paid" | "waived" };
export type Offer = { feeMinor: number; currency: string; feeStatus: "due" | "waived"; waiverReason: string | null };
export type EngagementStatus = "requested" | "scheduled" | "in_progress" | "done" | "cancelled";
export type Engagement = { id: string; status: EngagementStatus; fee: Fee; waiverReason: string | null; preferredSlots: string[]; slotAt: string | null;
  coveredSteps: string[]; createdAt: string; closedAt: string | null };
export type OwnerView = { offer: Offer | null; engagement: Engagement | null };
export type Booked = { engagementId: string; fee: Fee; waiverReason: string | null; paymentLink: string | null };

/** Must equal WhiteGloveService.STEP_UP_TARGET: the MFA confirmation books the setup once. */
export const WHITE_GLOVE_STEP_UP = "white-glove:book";
export const MAX_SLOTS = 3;

/** A booking is open until Nabd closes or cancels it: one per clinic (server: 409 ENGAGEMENT_OPEN). */
export const isOpen = (e: Engagement | null): boolean => !!e && (e.status === "requested" || e.status === "scheduled" || e.status === "in_progress");

/** datetime-local values (the owner's own time) -> ISO instants; blanks dropped, past times and more than 3 refused (null). */
export function slotsToIso(values: string[], now: Date = new Date()): string[] | null {
  const picked = values.map((v) => v.trim()).filter(Boolean);
  if (picked.length === 0 || picked.length > MAX_SLOTS) return null;
  const out: string[] = [];
  for (const v of picked) {
    const d = new Date(v);
    if (Number.isNaN(d.getTime()) || d.getTime() <= now.getTime()) return null;
    out.push(d.toISOString());
  }
  return out;
}
