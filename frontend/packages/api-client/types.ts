// Response types for endpoints the migrated flows call. Hand-patched: the generated openapi.d.ts replaces this
// file when spec-first lands (T-089). Each block names the endpoint it mirrors.

/** Problem+JSON body: the generated api/openapi.yaml Problem (FEF-013: one definition). */
export type { Problem } from "./src/generated/types.ts";

// TODO(T-089): GET/POST /billing/checkout/{queueEntryId}, /billing/invoices/{id}, /billing/otc-charges, /billing/otc-checkout (CheckoutController)
export type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; taxRatePercent: number; stockQty?: number | null; isRx?: boolean };
export type PrescribedItem = { drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null;
  prescriptionItemId: string; quantity: number | null; remainingQty: number }; // PHL-001
export type RxLine = { prescriptionItemId: string; patientId: string; patientName: string; drugName: string; dosage: string | null;
  quantity: number | null; remainingQty: number; signedAt: string | null }; // PHL-001 POST /pharmacy/rx-lines/search
export type CheckoutContext = {
  queueEntryId: string; patientName: string; doctorName: string; visitType: string;
  followUpEligible: boolean; currency: string; charges: Charge[]; pendingProcedures: Charge[];
  prescribedItems: PrescribedItem[]; departmentId: string; queueStatus: string;
};
export type OtcContext = { currency: string; charges: Charge[] };
export type FlowStep = { stepType: string };
export type LineItem = { chargeCode: string; chargeName: string; category: string; quantity: number; unitPrice: number; taxRatePercent: number; lineTotal?: number;
  listUnitPrice?: number | null; overrideReason?: string | null; // from the server (FIN-003)
  override?: { unitPrice: number; reasonCode: string }; // picked here, sent as the override
  prescriptionItemId?: string; // PHL-001: required for an isRx line
};
export type Payment = { id: string; method: string; amount: number; recordedAt: string };
export type Invoice = {
  id: string; invoiceNumber: string; patientName: string; doctorName?: string; currency: string;
  subtotal: number; discount: number; tax: number; roundOff: number; total: number; paid: number;
  balanceDue: number; status: string; lineItems: LineItem[]; payments: Payment[];
};

// TODO(T-089): GET /queue/wait-estimate, GET /staff/roster, POST /patients (queue rows: QueueEntry in @nabd/domain)
export type WaitEstimate = { estimatedMinutes?: number };
export type StaffOption = { id: string; name: string };
export type CreatedPatient = { id: string };
export type DuplicateCandidate = { patientId: string; name: string; phone: string; matchScore: number };
