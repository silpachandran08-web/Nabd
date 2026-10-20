// FEF-014: the augmentation file (FEF-007's hand-patched types, now derived from the generated spec types).
// A line `export type X = G.Y;` is wired: no gap. Every other `export type` is ONE augmentation entry, a gap in
// api/openapi/core.yaml that its owning squad closes in the backend, then the entry becomes a plain alias.
// The count must reach 0 by Phase 1 S5; tests/unit/s12-fef014-codegen.test.mjs ratchets it (it may only go down).
// Because each entry maps over the generated type, a field renamed in the spec disappears here too: every consumer fails tsc.
import type * as G from "./src/generated/types.ts";

/** Req: fields the server always sends but the spec marks optional (springdoc emits no `required` on response DTOs).
 * Nul: fields the server can send as null but the spec does not mark nullable. Keys must exist in T. */
type Patch<T, Req extends keyof T, Nul extends keyof T = never> = {
  [P in keyof T as P extends Req ? P : never]-?: Exclude<T[P], undefined> | (P extends Nul ? null : never);
} & {
  [P in keyof T as P extends Req ? never : P]?: T[P] | (P extends Nul ? null : never);
};

// ---- wired (no gap) ----
export type Problem = G.Problem;
export type WaitEstimate = G.WaitEstimateResponse;

// ---- augmentation entries (gap: grants checked in services, not in x-required-permission; owner: billing squad, CheckoutController) ----
export type AppPermission = G.Permission | "billing.discount";

// ---- augmentation entries (gap: required / nullable not in the spec; owner: billing-pharmacy squad, CheckoutController / RxLineController DTOs) ----
export type Charge = Patch<G.ChargeResponse, "id" | "code" | "name" | "category" | "baseAmount" | "followUpAmount" | "taxRatePercent", "followUpAmount" | "stockQty">;
export type PrescribedItem = Patch<G.PrescribedItemResponse, "drugName" | "dosage" | "frequency" | "duration" | "instructions" | "prescriptionItemId" | "quantity" | "remainingQty",
  "dosage" | "frequency" | "duration" | "instructions" | "quantity">; // PHL-001
export type RxLine = Patch<G.RxLineResponse, "prescriptionItemId" | "patientId" | "patientName" | "drugName" | "dosage" | "quantity" | "remainingQty" | "signedAt",
  "dosage" | "quantity" | "signedAt">; // PHL-001 POST /pharmacy/rx-lines/search
export type CheckoutContext = Omit<Patch<G.CheckoutContextResponse, "queueEntryId" | "patientName" | "doctorName" | "visitType" | "followUpEligible" | "currency"
  | "departmentId" | "queueStatus">, "charges" | "pendingProcedures" | "prescribedItems"> & { charges: Charge[]; pendingProcedures: Charge[]; prescribedItems: PrescribedItem[] };
export type OtcContext = Omit<Patch<G.OtcChargesResponse, "currency">, "charges"> & { charges: Charge[] };
export type FlowStep = Patch<G.FlowStepResponse, "stepType">;
/** Also a UI model: `override` is what the cashier picked (sent as LineItemRequest.override); prescriptionItemId is
 * in LineItemRequest but not in LineItemResponse (gap: the response should echo it, PHL-001). */
export type LineItem = Patch<G.LineItemResponse, "chargeCode" | "chargeName" | "category" | "quantity" | "unitPrice" | "taxRatePercent", "listUnitPrice" | "overrideReason">
  & { override?: { unitPrice: number; reasonCode: string }; prescriptionItemId?: string };
export type Payment = Patch<G.PaymentResponse, "id" | "method" | "amount" | "recordedAt">;
export type Invoice = Omit<Patch<G.InvoiceResponse, "id" | "invoiceNumber" | "patientName" | "currency" | "subtotal" | "discount" | "tax" | "roundOff" | "total"
  | "paid" | "balanceDue" | "status">, "lineItems" | "payments"> & { lineItems: LineItem[]; payments: Payment[] };

// ---- augmentation entries (owner: front-desk squad, QueueController / StaffController / PatientController) ----
// FEF-013: queue rows (GET /queue, POST /queue/check-in) use the one QueueEntry in @nabd/domain.
export type StaffOption = Patch<G.StaffRosterEntry, "id" | "name">;
/** gap: POST /patients returns an untyped Map in the spec (Record<string, unknown>). */
export type CreatedPatient = { id: string };
/** gap: the 409 duplicate-patient Problem's `candidates` extension is not in the spec. */
export type DuplicateCandidate = { patientId: string; name: string; phone: string; matchScore: number };
