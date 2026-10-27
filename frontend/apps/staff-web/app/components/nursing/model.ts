import type { ActivityEntryResponse, AdministrationOrderResponse, ProcedureOrderResponse, StaffRosterEntry } from "@nabd/api-client/generated";
import { VITAL_FIELDS, age, type QueueEntry, type VitalKey } from "@nabd/domain";
import { ApiError } from "../../lib/api";
import { clinicToday } from "../../lib/clinicTime";

// FEF-031: types, constants and pure helpers of the nursing screen (moved unchanged from the old page).
export type Entry = Omit<QueueEntry, "gender" | "ageYears">;
export type Row = Entry & {
  patientName: string; patientMrn: string; doctorName: string;
  allergies: string[]; chronicConditions: string[];
  lastVitalsAt: string | null; vitalsOutOfRange: boolean;
  dob: string | null; gender: string | null; hasAppointment: boolean; ageYears: number | null; wait: number;
  // The patient record could not be read (no patients:view, 404, 5xx): allergies are UNKNOWN, not absent.
  flagsUnknown: boolean;
};
// A patient's clinical-flags chips: allergy (danger), chronic condition (warn), presenting
// complaint (neutral info). "Urgent" is reserved for the queue's priority flag (rendered
// separately) — a routine complaint such as "cough" must never look urgent.
export type Flag = { kind: "danger" | "warn" | "info"; text: string };
export function flagsFor(r: { allergies: string[]; chronicConditions: string[]; presentingComplaint?: string | null; priorityReason?: string | null }): Flag[] {
  return [
    ...r.allergies.map((a): Flag => ({ kind: "danger", text: `Allergy: ${a}` })),
    ...r.chronicConditions.map((c): Flag => ({ kind: "warn", text: c })),
    ...(r.presentingComplaint && r.presentingComplaint.trim().toLowerCase() !== (r.priorityReason ?? "").trim().toLowerCase()
      ? [{ kind: "info", text: r.presentingComplaint } as Flag] : []),
  ];
}

// "58 M" from the patient's real dob/gender; either part is simply left out when unknown.
export function ageSex(dob: string | null, gender: string | null, ageYears?: number | null): string {
  const parts: string[] = [];
  if (ageYears != null) parts.push(String(ageYears));
  else if (dob) {
    const a = age(dob, clinicToday());
    if (a >= 0) parts.push(String(a));
  }
  const g = (gender ?? "").trim().charAt(0).toUpperCase();
  if (g) parts.push(g);
  return parts.filter(Boolean).join(" ");
}
export const FLAG_ICON = { danger: "alert", warn: "pulse", info: "info" } as const;

export function statusLabel(s: string): string {
  const t = s.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}
export const hhmm = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

// The soft ranges are adult ranges: for a child (or when the age is unknown) showing "outside usual
// range" would mislead, so no hint is given; the server's age-banded flags still appear after save.
export function isAdult(dob: string | null): boolean {
  return !!dob && age(dob, clinicToday()) >= 18;
}

export function softWarning(key: VitalKey, raw: string, adult: boolean): string | null {
  const d = VITAL_FIELDS.find((x) => x.key === key);
  if (!adult || !d?.soft || raw.trim() === "") return null;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < d.min || n > d.max) return null;
  return n < d.soft[0] || n > d.soft[1] ? `Outside usual range (${d.soft[0]}–${d.soft[1]} ${d.unit}) — recheck` : null;
}
// Same catalogue as arrivals' Mark priority — nursing can only acknowledge an existing flag
// today, never raise one; this is the missing "Mark urgent" quick action from DESIGN.md's
// Vitals Worklist toolbar, using the same POST /queue/{id}/reorder reception already drives.
export const URGENT_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;
export type VitalsFilter = "due" | "recorded" | "all";
export type StaffOption = StaffRosterEntry & { id: string; name: string };
export type AdministrationOrder = AdministrationOrderResponse & { id: string };
export type ProcedureOrder = ProcedureOrderResponse & { id: string };
export type ActivityEntry = ActivityEntryResponse & { occurredAt: string };
/** The Problem detail of a failed call, else the fallback (network errors included). */
export const detailOf = (e: unknown, fallback: string) => (e instanceof ApiError && e.detail) || fallback;
// REC-060: recording vitals (blank fields go out as null, so the server keeps stored values) needs
// clinical:create or clinical:edit; the server 403s (vitals-correction-forbidden) a changed recorded value
// without clinical:edit, and the capture form never pre-fills or offers to change recorded values.
export const PERM = { vitalsCreate: "clinical:create", vitalsWrite: "clinical:edit", vitalsRead: "clinical:view", create: "nursing:create", edit: "nursing:edit", packages: "packages:view", queueView: "queue:view", queueEdit: "queue:edit" } as const;
export type Tab = "vitals" | "priority" | "administration" | "procedures" | "activity";

export const POLL_MS = 15_000;
export const TABS: { key: Tab; label: string }[] = [
  { key: "vitals", label: "Arrivals & Vitals" },
  { key: "priority", label: "Priority Patients" },
  { key: "administration", label: "Administration Orders" },
  { key: "procedures", label: "Today's Procedures" },
  { key: "activity", label: "Completed Activity" },
];

export function validTab(t: string | null): Tab {
  return TABS.some((x) => x.key === t) ? (t as Tab) : "vitals";
}

export function validFilter(f: string | null): VitalsFilter {
  return f === "recorded" || f === "all" ? f : "due";
}

export function waitMinutes(createdAt: string): number {
  return Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
}

// Mirrors DepartmentService.resolveStatusSequence on the backend — see checkout/[queueEntryId]/page.tsx
// for the same helper, duplicated locally rather than shared since it's a handful of lines.
export const STATUSES_FOR_STEP_TYPE: Record<string, string[]> = {
  billing: ["billing_pending"],
  vitals: ["vitals_pending", "vitals_done"],
  consultation: ["in_consult"],
  procedures: ["procedures_pending"],
};
export const DEFAULT_STEP_TYPES = ["vitals", "consultation"];

export function resolveSequence(steps: { stepType: string }[]): string[] {
  const stepTypes = steps.length > 0 ? steps.map((s) => s.stepType) : DEFAULT_STEP_TYPES;
  const sequence = ["checked_in", "waiting"];
  for (const t of stepTypes) sequence.push(...(STATUSES_FOR_STEP_TYPE[t] ?? []));
  sequence.push("checkout_pending", "completed");
  return sequence;
}
