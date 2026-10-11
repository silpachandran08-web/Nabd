// Types and helpers shared by the consultation workspace and its components.
// Shapes match GET /v1/queue, /patients/{id}, /clinical/* as already consumed by consult,
// nursing and patients pages.

export type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

export type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string; departmentId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
  createdAt?: string; source?: string; presentingComplaint?: string | null;
};
export type PatientDetail = {
  id: string; mrn: string; name: string; phone: string; dob: string; gender: string; status: string;
  allergies: string[]; chronicConditions: string[]; activePackages: number; outstandingBalance?: number; lastVisitAt: string | null;
};
export type Row = QueueEntry & { patientName: string; patient: PatientDetail | null };

export type PrescriptionItem = {
  id?: string; drugName: string; dosage: string; frequency: string; duration: string;
  instructions: string; allergyOverrideReason: string; allergyWarning?: string | null;
  pregnancyWarning?: string | null; controlledSubstanceWarning?: string | null;
};
export type Prescription = { id: string; status: string; signedAt: string | null; items: PrescriptionItem[] };
export type Problem = { title: string; detail: string };

export type Allergy = { id: string; substance: string; severity: string; reaction: string | null; active: boolean };
export type Condition = { id: string; condition: string; status: string; reviewDueDate: string | null };
export type Encounter = { queueEntryId: string; occurredAt: string; diagnosis: string | null; assessment: string | null; medications: string | null };
export type Vitals = {
  recordedAt: string | null;
  heightCm?: number | null; weightKg?: number | null; bpSystolic?: number | null; bpDiastolic?: number | null;
  pulseBpm?: number | null; tempCelsius?: number | null; spo2Percent?: number | null;
  abnormalFlags: string[];
};

export const WAITING_STATUSES = ["checked_in", "waiting", "vitals_pending", "vitals_done"];
export const DONE_STATUSES = ["checkout_pending", "completed", "no_show"];

export function statusLabel(status: string): string {
  return status.replace(/_/g, " ");
}

export function age(dob: string): number {
  const d = new Date(dob);
  const now = new Date();
  let a = now.getFullYear() - d.getFullYear();
  if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) a--;
  return a;
}

export function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase() ?? "").join("");
}

export function minutesSince(iso: string | undefined | null, now: number): number | null {
  if (!iso) return null;
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return null;
  return Math.max(0, Math.round((now - t) / 60000));
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
