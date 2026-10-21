// Types and helpers shared by the consultation workspace and its components.
// Shapes match GET /v1/queue, /patients/{id}, /clinical/* as already consumed by consult,
// nursing and patients pages.

// FEF-013: queue/patient shapes, status lists and age/initials/minutesSince live in @nabd/domain; re-exported for the consult files.
import { queueStatusLabel, type PatientSummary as PatientDetail, type QueueEntry } from "@nabd/domain";
export { age, DONE_STATUSES, initials, minutesSince, WAITING_STATUSES, type QueueEntry } from "@nabd/domain";
export type { AuthedFetch } from "../../lib/apiClient";
export type { Problem } from "@nabd/api-client/types";
export type { PatientDetail };
export type Row = QueueEntry & { patientName: string; patient: PatientDetail | null };

// A safety warning on one prescription line (RxWarningResponse). Lines that requiresDecision block
// signing until a decision with a named reason is recorded (who/when are set by the server).
export type RxReasonOption = { code: string; label: string };
export type RxDecision = { kind: string; reasonCode: string; reasonLabel: string | null; note: string | null; by: string | null; byName: string | null; at: string | null };
export type RxWarning = {
  kind: "allergy" | "pregnancy" | "controlled"; severity: string; message: string; matched: string;
  requiresDecision: boolean; resolved: boolean; reasonOptions: RxReasonOption[]; decision: RxDecision | null;
};
export type RxDecisionInput = { kind: string; reasonCode: string; note?: string };
export type PrescriptionItem = {
  id?: string; drugName: string; dosage: string; frequency: string; duration: string;
  instructions: string; allergyOverrideReason?: string | null; allergyWarning?: string | null;
  pregnancyWarning?: string | null; controlledSubstanceWarning?: string | null;
  warnings?: RxWarning[]; overrides?: RxDecisionInput[];
  // DOC-033 structured pad fields (null on lines saved before they existed)
  brandName?: string | null; sigPattern?: string | null; route?: string | null; quantity?: number | null; quantityUnit?: string | null;
};
export type DrugSuggestion = { name: string; source: "pharmacy" | "history"; code: string | null; inStock: boolean | null; prescriptionOnly: boolean | null };
// DOC-R3: allergy statement captured at signing. Null on drafts and on prescriptions signed before it was captured.
export type PrescriptionAllergySnapshot = {
  state: "recorded" | "none_documented" | "not_assessed";
  allergies: { substance: string; severity: string; reaction: string | null }[];
  documentedBy: string | null; documentedByName: string | null; documentedAt: string | null;
};
export type Prescription = {
  id: string; status: string; signedAt: string | null; items: PrescriptionItem[]; unresolvedWarnings?: number;
  allergySnapshot?: PrescriptionAllergySnapshot | null;
  /** CLN-004: dictionary version active at signing; null for drafts and older prescriptions. */
  safetyDictionaryVersion?: string | null;
};

export type Allergy = {
  id: string; substance: string; severity: string; reaction: string | null; active: boolean;
  category?: string | null; source?: string | null; recordedBy?: string; recordedByName?: string | null; recordedAt?: string; updatedAt?: string | null;
};
export type PregnancyStatus = { status: "not_documented" | "not_pregnant" | "pregnant" | "lactating" | "unknown"; expectedDeliveryDate: string | null; recordedByName: string | null; recordedAt: string | null };
export type AllergyStatus = { state: "recorded" | "none_documented" | "not_assessed"; activeCount: number; documentedByName: string | null; documentedAt: string | null };
export type Condition = { id: string; condition: string; status: string; reviewDueDate: string | null };
export type Encounter = { queueEntryId: string; occurredAt: string; diagnosis: string | null; assessment: string | null; medications: string | null };
export type Vitals = {
  recordedAt: string | null;
  heightCm?: number | null; weightKg?: number | null; bpSystolic?: number | null; bpDiastolic?: number | null;
  pulseBpm?: number | null; tempCelsius?: number | null; spo2Percent?: number | null;
  abnormalFlags: string[];
};

// DOC-044: one label map for queue statuses (consultFlow.QUEUE_STATUS_LABEL).
export function statusLabel(status: string): string {
  return queueStatusLabel(status);
}

export function daysAgoLabel(iso: string | null): string {
  if (!iso) return "No previous visit";
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  const when = new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
  if (days <= 0) return `Today · ${when}`;
  return `${days} day${days === 1 ? "" : "s"} ago · ${when}`;
}

// "Amoxicillin · 500 mg · TDS · 5 days" — dose, sig and duration as recorded, never reformatted.
export function rxItemLine(i: { drugName: string; dosage?: string | null; frequency?: string | null; duration?: string | null }): string {
  return [i.drugName, i.dosage, i.frequency, i.duration].filter((p) => p && String(p).trim() !== "").join(" · ");
}

export const SEVERITY_ORDER: Record<string, number> = { severe: 0, moderate: 1, mild: 2 };
