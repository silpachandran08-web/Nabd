// FEF-013/FEF-014: the domain shapes several screens share, DERIVED from the generated wire types of GET /v1/queue
// (QueueEntryResponse) and GET /v1/patients/{id} (PatientDetailResponse): a field renamed or dropped in the spec
// disappears here too, so every consumer fails tsc. Only types come from api-client (eslint boundaries allow it).
import type { PatientDetailResponse, QueueEntryResponse } from "@nabd/api-client/generated";

/** Req: fields the server always sends but the spec marks optional (springdoc emits no `required` on response DTOs).
 * Nul: fields the server can send as null. Keys must exist in T, so a spec rename also breaks this file. */
type Patch<T, Req extends keyof T, Nul extends keyof T = never> = {
  [P in keyof T as P extends Req ? P : never]-?: Exclude<T[P], undefined> | (P extends Nul ? null : never);
} & {
  [P in keyof T as P extends Req ? never : P]?: T[P] | (P extends Nul ? null : never);
};

/** One GET /v1/queue row. status and source stay strings: a newer backend may send a value this build does not know.
 * REC-011 / NUR-012 fields (visitType, ageYears, gender, doctorName, waitMinutes) stay optional: an older backend shows less. */
export type QueueEntry = Patch<QueueEntryResponse,
  "id" | "appointmentId" | "patientId" | "doctorId" | "departmentId" | "queueDate" | "tokenNumber" | "status" | "priority" | "priorityReason"
  | "createdAt" | "source" | "presentingComplaint" | "priorityFlaggedBy" | "priorityFlaggedAt" | "priorityAcknowledgedBy" | "priorityAcknowledgedAt",
  "appointmentId" | "priorityReason" | "presentingComplaint" | "priorityFlaggedBy" | "priorityFlaggedAt" | "priorityAcknowledgedBy"
  | "priorityAcknowledgedAt" | "visitType" | "ageYears" | "gender" | "doctorName" | "waitMinutes">;

/** GET /v1/patients/{id} as the consult, My Day and patient screens read it. outstandingBalance is omitted when the
 * caller's field grants restrict financial data (NB-052). */
export type PatientSummary = Patch<PatientDetailResponse,
  "id" | "mrn" | "name" | "phone" | "dob" | "gender" | "status" | "allergies" | "chronicConditions" | "activePackages" | "lastVisitAt",
  "lastVisitAt">;
