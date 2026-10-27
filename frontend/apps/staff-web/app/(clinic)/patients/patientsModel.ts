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
export type Perms = { clinicalView: boolean; clinicalEdit: boolean; dentalView: boolean; dentalEdit: boolean; billingView: boolean; checkIn: boolean; register: boolean; patientsEdit: boolean; merge: boolean };
export const NO_PERMS: Perms = { clinicalView: false, clinicalEdit: false, dentalView: false, dentalEdit: false, billingView: false, checkIn: false, register: false, patientsEdit: false, merge: false };

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

export const FDI_TEETH = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28,
  48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38];
export const TOOTH_STATUSES = ["healthy", "decayed", "filled", "missing", "crown", "root_canal"] as const;
// NB-122: the standard Universal Numbering System mapping for permanent teeth — a fixed notation
// conversion (not clinical judgment), so it's safe to hardcode. Storage always stays keyed by FDI;
// this only changes what's displayed.
export const FDI_TO_UNIVERSAL: Record<number, number> = {
  18: 1, 17: 2, 16: 3, 15: 4, 14: 5, 13: 6, 12: 7, 11: 8,
  21: 9, 22: 10, 23: 11, 24: 12, 25: 13, 26: 14, 27: 15, 28: 16,
  38: 17, 37: 18, 36: 19, 35: 20, 34: 21, 33: 22, 32: 23, 31: 24,
  41: 25, 42: 26, 43: 27, 44: 28, 45: 29, 46: 30, 47: 31, 48: 32,
};

export const selfId = () => decodeAccessToken(localStorage.getItem("nabd_access_token") ?? "")?.sub ?? "";

/** Actions a role can't take are absent, not disabled (RBAC matrix rule). UI gating only; the server still enforces every grant. */
export const readPerms = (): Perms => ({
  clinicalView: can("clinical:view"), clinicalEdit: can("clinical:edit"),
  dentalView: can("specialty_dental:view"), dentalEdit: can("specialty_dental:edit"),
  billingView: can("billing:view"), checkIn: can("queue:create"), register: can("patients:create"), patientsEdit: can("patients:edit"), merge: can("patients:delete"),
});
