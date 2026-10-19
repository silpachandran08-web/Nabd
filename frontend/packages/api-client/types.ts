// Response types for endpoints the migrated flows call. Hand-patched: the generated openapi.d.ts replaces this
// file when spec-first lands (T-089). Each block names the endpoint it mirrors.

/** Problem+JSON body (api/openapi.yaml Problem). */
export type Problem = { type?: string; title?: string; status?: number; detail?: string; code?: string; traceId?: string; errors?: { field: string; message: string }[] };

// TODO(T-089): GET/POST /billing/checkout/{queueEntryId}, /billing/invoices/{id}, /billing/otc-charges, /billing/otc-checkout (CheckoutController)
export type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; taxRatePercent: number; stockQty?: number | null };
export type PrescribedItem = { drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null };
export type CheckoutContext = {
  queueEntryId: string; patientName: string; doctorName: string; visitType: string;
  followUpEligible: boolean; currency: string; charges: Charge[]; pendingProcedures: Charge[];
  prescribedItems: PrescribedItem[]; departmentId: string; queueStatus: string;
};
export type OtcContext = { currency: string; charges: Charge[] };
export type FlowStep = { stepType: string };
export type LineItem = { chargeCode: string; chargeName: string; category: string; quantity: number; unitPrice: number; taxRatePercent: number; lineTotal?: number };
export type Payment = { id: string; method: string; amount: number; recordedAt: string };
export type Invoice = {
  id: string; invoiceNumber: string; patientName: string; doctorName?: string; currency: string;
  subtotal: number; discount: number; tax: number; roundOff: number; total: number; paid: number;
  balanceDue: number; status: string; lineItems: LineItem[]; payments: Payment[];
};

// TODO(T-089): POST /queue/check-in, GET /queue, GET /queue/wait-estimate, GET /staff/roster, POST /patients
export type QueueEntry = { id?: string; doctorId?: string; tokenNumber: number; priority?: boolean; status?: string; createdAt?: string };
export type WaitEstimate = { estimatedMinutes?: number };
export type StaffOption = { id: string; name: string };
export type CreatedPatient = { id: string };
export type DuplicateCandidate = { patientId: string; name: string; phone: string; matchScore: number };
