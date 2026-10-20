// FEF-013: the domain shapes several screens share. Fields match GET /v1/queue (QueueEntryResponse) and
// GET /v1/patients/{id} (PatientResponse) as the app reads them; the generated api-client types stay the wire contract.

/** One GET /v1/queue row. status and source stay strings: a newer backend may send a value this build does not know. */
export type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string; departmentId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
  createdAt: string; source: string; presentingComplaint: string | null;
  priorityFlaggedBy: string | null; priorityFlaggedAt: string | null; priorityAcknowledgedBy: string | null; priorityAcknowledgedAt: string | null;
  // REC-011 / NUR-012: optional so an older backend just shows less.
  visitType?: string | null; ageYears?: number | null; gender?: string | null; doctorName?: string | null; waitMinutes?: number | null;
};

/** GET /v1/patients/{id} as the consult, My Day and patient screens read it. */
export type PatientSummary = {
  id: string; mrn: string; name: string; phone: string; dob: string; gender: string; status: string;
  allergies: string[]; chronicConditions: string[]; activePackages: number;
  outstandingBalance?: number; // NB-052: omitted when the caller's field grants restrict financial data
  lastVisitAt: string | null;
};
