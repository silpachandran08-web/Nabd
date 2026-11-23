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
  kind: "allergy" | "pregnancy" | "controlled" | "ddi" | "duplicate"; severity: string; message: string; matched: string;
  requiresDecision: boolean; resolved: boolean; reasonOptions: RxReasonOption[]; decision: RxDecision | null;
  // CLN-032, ddi and duplicate only (the knowledge base's own data; any may be null): the other medicine and where it is
  // (otherLineRef = 0-based line of this prescription, null for an active medicine), why, what to do, "<KB> <month>".
  otherLineRef?: number | null; otherDrug?: string | null; otherFrom?: "draft" | "rx" | "reported" | null;
  mechanism?: string | null; management?: string | null; source?: string | null; sourceRef?: string | null; totalDailyDose?: string | null;
};
export type RxDecisionInput = { kind: string; reasonCode: string; note?: string; matched?: string };
export type PrescriptionItem = {
  id?: string; drugName: string; dosage: string; frequency: string; duration: string;
  instructions: string; allergyOverrideReason?: string | null; allergyWarning?: string | null;
  pregnancyWarning?: string | null; controlledSubstanceWarning?: string | null;
  warnings?: RxWarning[]; overrides?: RxDecisionInput[];
  // DOC-033 structured pad fields (null on lines saved before they existed)
  brandName?: string | null; sigPattern?: string | null; route?: string | null; quantity?: number | null; quantityUnit?: string | null;
  // CLN-021: drug master code (null/absent = uncoded free text) and the generic name snapshot taken when it was saved
  drugId?: string | null; coded?: boolean; genericName?: string | null;
  // CLN-024: what the doctor asked for (null = the class default) and what applies (server-computed, read-only)
  validityDays?: number | null; refills?: number | null; validity?: RxLineValidity | null;
  // CLN-032: "checked" | "not_checked" (free text: nothing can be said about it); null = no knowledge base, nothing was checked
  interactionCheck?: "checked" | "not_checked" | null;
};
/** CLN-024: a line's validity. Draft: the values signing would apply now and the class limits (fixed = a controlled class whose values
 * apply whatever is entered). Signed: the applied days/refills and validUntil; the limits are null. days null = no class rule. */
export type RxLineValidity = {
  days: number | null; maxDays: number | null; refills: number; maxRefills: number | null; fixed: boolean | null; classId: string | null; validUntil: string | null;
};
export type DrugSuggestion = {
  name: string; source: "master" | "pharmacy" | "history"; code: string | null; inStock: boolean | null; prescriptionOnly: boolean | null;
  // CLN-020: master rows (server) and client-index rows carry the product parts
  drugId?: string | null; display?: string | null; generic?: string | null; brand?: string | null; strength?: string | null; form?: string | null;
};
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
  /** CLN-022: the visit it belongs to and the signer's name (the pad's "Repeat last (date, doctor)"). */
  queueEntryId?: string; signedByName?: string | null;
  /** T1-BUG-1 (CLN-023): set when the prescriber revoked it; never offered as "Repeat last". */
  revokedAt?: string | null;
};

export type Allergy = {
  id: string; substance: string; severity: string; reaction: string | null; active: boolean;
  category?: string | null; source?: string | null; recordedBy?: string; recordedByName?: string | null; recordedAt?: string; updatedAt?: string | null;
  unconfirmed?: boolean; // ADM-012: imported from a legacy list, a doctor still has to confirm it
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

/** `tx` is the `consult.safetyStrip` translator. */
export function daysAgoLabel(tx: (key: string, values?: Record<string, string | number>) => string, iso: string | null): string {
  if (!iso) return tx("noPreviousVisit");
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  const date = new Date(iso).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
  return days <= 0 ? tx("visitToday", { date }) : tx("visitDaysAgo", { count: days, date });
}

// "Amoxicillin · 500 mg · TDS · 5 days" — dose, sig and duration as recorded, never reformatted.
export function rxItemLine(i: { drugName: string; dosage?: string | null; frequency?: string | null; duration?: string | null }): string {
  return [i.drugName, i.dosage, i.frequency, i.duration].filter((p) => p && String(p).trim() !== "").join(" · ");
}

export const SEVERITY_ORDER: Record<string, number> = { severe: 0, moderate: 1, mild: 2 };
