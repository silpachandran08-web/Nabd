// IDC-012: the desk "claimed at desk" flow. The problem `code` values come from ProfileClaimService / ProfileClaimController.

export type KeepScope = "NONE" | "BOOK_ONLY" | "BOOK_AND_RECORDS";
export type ReviewFilter = "due" | "overdue";

/** GET /v1/patients/guardian-reviews-due?status=... (due = turns 18 within 14 days, overdue = already 18 with a guardian on file). */
export const reviewsPath = (f: ReviewFilter) => `/patients/guardian-reviews-due?status=${f}`;

/** A due row is still a minor: nothing to claim yet. An overdue row can be claimed at the desk, kept or revoked. */
export const canClaimAtDesk = (f: ReviewFilter) => f === "overdue";

/** Message for a failed send or claim; anything that is not an HTTP problem is a network failure. */
export function claimError(e: unknown, step: "send" | "claim"): string {
  const p = e && typeof e === "object" && "status" in e ? (e as { status: number; code?: string }) : null;
  if (!p) return "Couldn't reach the server. Nothing was changed.";
  if (p.code === "own-phone-required") return "The patient has no phone number of their own on file. Add it to the profile first, then send the code.";
  if (p.code === "claim-not-open") return "The patient is not 18 yet. The profile can be claimed from the birthday.";
  if (p.status === 401) return "That code is wrong or has expired. Send a new one.";
  if (p.status === 429) return "A code was sent a moment ago. Wait 30 seconds before sending another.";
  if (p.status === 410) return "This handover has already ended.";
  return step === "send" ? "Couldn't send the code. Nothing was changed." : "Couldn't record the claim. Guardian access is unchanged.";
}
