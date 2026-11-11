// Shapes of /v1/nursing/administration-orders and /v1/nursing/procedure-orders (NursingController,
// AdministrationOrderResponse, ProcedureOrderResponse). Nothing here is invented: the lists are
// clinic-wide for "today", so the consult filters them to the open visit by queueEntryId.
export type AdminOrder = {
  id: string; queueEntryId: string; patientName: string; orderedByName: string; drugName: string; dose: string;
  route: string | null; site: string | null; status: string; recordedByName: string | null; witnessedByName: string | null;
  refuseReason: string | null; recordedAt: string | null; createdAt: string;
};
export type ProcOrder = {
  id: string; queueEntryId: string; patientName: string; orderedByName: string; chargeCode: string; chargeName: string;
  prepNotes: string | null; consentNote: string | null; status: string; billed: boolean; completedByName: string | null;
  completedAt: string | null; createdAt: string; consentSignedName: string | null; consentRecordedByName: string | null;
  consentSignedAt: string | null;
  // CLN-014: the signed form (null for a typed-name consent), a withdrawal, and the patient's language for the form
  patientId?: string; consentId?: string | null; consentSha256?: string | null; consentWithdrawnAt?: string | null;
  consentWithdrawReason?: string | null; patientLanguage?: string | null;
};
// AdministrationOrderRequest.route pattern: IM|IV|SC|infusion|oral|topical (route is optional).
export const ROUTES = ["IM", "IV", "SC", "infusion", "oral", "topical"] as const;
export type ChargeHead = { code: string; name: string; active: boolean };

export function adminStatus(o: AdminOrder): { tone: "ok" | "warn" | "danger"; label: string } {
  if (o.status === "administered") return { tone: "ok", label: o.recordedByName ? `Given by ${o.recordedByName}` : "Given" };
  if (o.status === "refused") return { tone: "danger", label: o.refuseReason ? `Refused: ${o.refuseReason}` : "Refused" };
  return { tone: "warn", label: "Waiting for the nurse" };
}
export function procStatusLabel(status: string): string {
  return ({ ordered: "Ordered", prepped: "Ready", completed: "Completed", cancelled: "Cancelled" } as Record<string, string>)[status] ?? status;
}
export const hasConsent = (p: ProcOrder) => !!p.consentSignedAt && !p.consentWithdrawnAt;
