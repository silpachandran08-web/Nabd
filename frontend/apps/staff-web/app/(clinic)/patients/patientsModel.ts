import type {
  AllergyResponse, AllergyStatusResponse, ConditionResponse, EncounterResponse, PatientDetailResponse, PatientRegistryResponse, PatientRegistryRow,
  PrescriptionResponse, QueueEntryResponse, ToothHistoryEntryResponse, ToothResponse,
} from "@nabd/api-client/generated";
import type { PatientSummary } from "@nabd/domain";
import type { Full } from "../../lib/gen";
import { can } from "../../lib/permissions";
import { decodeAccessToken } from "../../lib/session";

// FEF-031: types, constants and pure helpers of the patient registry and drawer (moved unchanged from the old page).
export type PatientDetail = PatientSummary & Full<Pick<PatientDetailResponse, "isMinor" | "guardianId" | "guardianName" | "guardianConsentGrantedAt" | "lastVisit">,
  "guardianId" | "guardianName" | "guardianConsentGrantedAt" | "lastVisit">;
// GET /clinical/patients/{id}/allergies/status. "not_assessed" (nobody asked) is NOT "none_documented".
export type AllergyStatus = Full<AllergyStatusResponse, "documentedByName" | "documentedAt"> & { state: "recorded" | "none_documented" | "not_assessed" };
export type Allergy = Full<Pick<AllergyResponse, "id" | "substance" | "severity" | "active">> & Pick<AllergyResponse, "category" | "recordedByName" | "recordedAt" | "deactivatedReason" | "deactivatedAt" | "deactivatedByName"> & { reaction: string | null };
export const ALLERGY_CATEGORIES = [
  { id: "drug", label: "Drug / medicine" }, { id: "food", label: "Food" },
  { id: "environmental", label: "Environmental" }, { id: "other", label: "Other" },
];
export const categoryLabel = (id?: string | null) => ALLERGY_CATEGORIES.find((c) => c.id === id)?.label;
export type Condition = Full<Pick<ConditionResponse, "id" | "condition" | "status" | "reviewDueDate">, "reviewDueDate">;
// GET /v1/patients/registry (PatientRegistryRow in the generated client).
export type RegistryRow = Full<PatientRegistryRow, "lastVisit" | "nextAppointment" | "nextDoctorName" | "nextDoctorId" | "nextAppointmentId" | "packageSessionsUsed" | "packageSessionsTotal" | "condition" | "allergy" | "queueToken" | "queueStatus">;
// OPS-008: keyset paging; page.nextCursor fetches the next page of the same filter + q.
export type Registry = Omit<Full<PatientRegistryResponse>, "rows" | "page" | "matchedTotal" | "hasMore"> & { rows: RegistryRow[]; matchedTotal?: number; page?: { nextCursor?: string | null } };
// Today's queue entry for the drawer patient (GET /v1/queue, QueueEntryResponse subset).
export type TodayVisit = Full<Pick<QueueEntryResponse, "id" | "patientId" | "doctorId" | "departmentId" | "status" | "tokenNumber">>;
export const VISIT_DONE = ["completed", "cancelled", "no_show", "transferred_out"];
// Drawer wording for today's visit (shorter than the queue board labels), keyed by queue status.
export const TODAY_STATUS_LABEL: Record<string, string> = {
  checked_in: "Waiting", waiting: "Waiting", billing_pending: "Billing", vitals_pending: "Waiting for vitals",
  vitals_done: "Ready for doctor", in_consult: "In consultation", procedures_pending: "Procedures", checkout_pending: "At checkout",
};
export const FILTERS: [string, string][] = [["all", "All"], ["recent", "Recently visited"], ["followup", "Follow-up due"],
  ["package", "Active package"], ["balance", "Outstanding balance"], ["duplicate", "Duplicate review"], ["archived", "Archived"]];
// Financial and duplicate-review filters are absent (not disabled) for roles that don't hold the grant.
export type Perms = { clinicalView: boolean; clinicalEdit: boolean; dentalView: boolean; dentalEdit: boolean; dermView: boolean; dermEdit: boolean; billingView: boolean; checkIn: boolean; register: boolean; patientsEdit: boolean; merge: boolean };
export const NO_PERMS: Perms = { clinicalView: false, clinicalEdit: false, dentalView: false, dentalEdit: false, dermView: false, dermEdit: false, billingView: false, checkIn: false, register: false, patientsEdit: false, merge: false };

/** "+91 ••••• 4412" — only the last four digits; used in the list and, until revealed, in the drawer. */
export function maskPhone(phone: string): string {
  const p = phone.replace(/\s/g, "");
  const digits = p.replace(/\D/g, "");
  if (digits.length < 8) return phone;
  const cc = p.startsWith("+966") ? "+966 " : p.startsWith("+91") ? "+91 " : "";
  return `${cc}••••• ${digits.slice(-4)}`;
}


export function nextLabel(r: RegistryRow): string {
  if (!r.nextAppointment) return "—";
  const at = new Date(r.nextAppointment);
  const when = r.nextAppointmentToday
    ? `Today ${at.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}`
    : at.toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" });
  return r.nextDoctorName ? `${when} · ${r.nextDoctorName}` : when;
}
export type Prescription = Full<Pick<PrescriptionResponse, "id" | "status" | "signedAt" | "items">, "signedAt" | "items"> & { items: Full<NonNullable<PrescriptionResponse["items"]>[number], "dosage" | "frequency" | "duration">[] };
export type Encounter = Full<EncounterResponse, "diagnosis" | "assessment" | "medications">;
export type Tooth = Full<ToothResponse, "note">;
export type ToothHistoryEntry = Full<ToothHistoryEntryResponse, "before" | "after">;

export { FDI_TEETH } from "../../lib/dental";
export const TOOTH_STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;

export const selfId = () => decodeAccessToken(localStorage.getItem("nabd_access_token") ?? "")?.sub ?? "";

/** Actions a role can't take are absent, not disabled (RBAC matrix rule). UI gating only; the server still enforces every grant. */
export const readPerms = (): Perms => ({
  clinicalView: can("clinical:view"), clinicalEdit: can("clinical:edit"),
  dentalView: can("specialty_dental:view"), dentalEdit: can("specialty_dental:edit"),
  dermView: can("specialty_derm:view"), dermEdit: can("specialty_derm:edit"), // CLN-047 photo timeline
  billingView: can("billing:view"), checkIn: can("queue:create"), register: can("patients:create"), patientsEdit: can("patients:edit"), merge: can("patients:delete"),
});
