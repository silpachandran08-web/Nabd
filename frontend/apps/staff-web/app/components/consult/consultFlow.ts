// Pure helpers for the consultation workspace (DOC-019, DOC-025, DOC-038, DOC-044). No React and no
// imports, so the rules can be exercised directly (scripts/test-consult-flow.mjs).

// ---- labels (DOC-044) -----------------------------------------------------------------------

// Same wording as the schedule board, so a status reads the same everywhere a doctor sees it.
export const QUEUE_STATUS_LABEL: Record<string, string> = {
  checked_in: "Checked in", waiting: "Waiting", billing_pending: "Billing pending",
  vitals_pending: "Vitals pending", vitals_done: "Ready for doctor", in_consult: "In consultation",
  procedures_pending: "Procedures pending", checkout_pending: "Checkout pending", completed: "Completed",
  no_show: "No-show", transferred_out: "Transferred",
};
export function queueStatusLabel(status: string): string {
  const known = QUEUE_STATUS_LABEL[status];
  if (known) return known;
  const words = status.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// The note is stored as SOAP fields. There is no specialty on the doctor, department or note yet, so
// the headings are the generic consultation ones; a specialty-titled note needs that data first.
export type NoteFieldId = "subjective" | "objective" | "assessment" | "plan" | "diagnosis";
export const NOTE_FIELD_LABEL: Record<NoteFieldId, string> = {
  subjective: "History (subjective)",
  objective: "Examination (objective)",
  assessment: "Assessment",
  plan: "Plan",
  diagnosis: "Diagnosis",
};
export const NOTE_TITLE = "Consultation note";

// ---- department pipeline (DOC-019) ----------------------------------------------------------

// Mirrors DepartmentService.resolveStatusSequence(): fixed anchors around the department's resolved
// step types (GET /departments/{id}/flow; an empty list means the default vitals + consultation).
const STATUSES_FOR_STEP: Record<string, string[]> = {
  billing: ["billing_pending"],
  vitals: ["vitals_pending", "vitals_done"],
  consultation: ["in_consult"],
  procedures: ["procedures_pending"],
};
const DEFAULT_STEPS = ["vitals", "consultation"];

export function statusSequence(stepTypes: string[]): string[] {
  const steps = stepTypes.length > 0 ? stepTypes : DEFAULT_STEPS;
  const out = ["checked_in", "waiting"];
  for (const st of steps) out.push(...(STATUSES_FOR_STEP[st] ?? []));
  out.push("checkout_pending", "completed");
  return out;
}

/** The status a visit must be in for the server to accept the move to in_consult, or null when the
 * pipeline has no consultation step. */
export function statusBeforeConsult(sequence: string[]): string | null {
  const i = sequence.indexOf("in_consult");
  return i > 0 ? sequence[i - 1] : null;
}

/** Fallback when the department flow could not be read: the default pipeline (vitals first). */
export const DEFAULT_START_STATUS = "vitals_done";

// ---- follow-up (DOC-038) --------------------------------------------------------------------

export type AppointmentLite = { id: string; patientId: string; doctorId: string; startTime: string; status: string; isFollowUp: boolean };

/** The soonest still-scheduled follow-up for this patient with this doctor, after `nowMs`. */
export function upcomingFollowUp(list: AppointmentLite[], doctorId: string, nowMs: number): AppointmentLite | null {
  let best: AppointmentLite | null = null;
  for (const a of list) {
    if (!a.isFollowUp || a.status !== "scheduled" || a.doctorId !== doctorId) continue;
    const t = new Date(a.startTime).getTime();
    if (Number.isNaN(t) || t <= nowMs) continue;
    if (!best || t < new Date(best.startTime).getTime()) best = a;
  }
  return best;
}

// ---- next patient (DOC-025) -----------------------------------------------------------------

export type QueueRowLite = { id: string; doctorId: string; status: string; tokenNumber: number };

/** The doctor's next patient after closing a visit: one already in consultation first, then the
 * lowest token that is ready to start. */
export function nextPatient<T extends QueueRowLite>(rows: T[], activeId: string, selfId: string, canStart: (r: T) => boolean): T | null {
  const mine = rows.filter((r) => r.id !== activeId && r.doctorId === selfId).sort((a, b) => a.tokenNumber - b.tokenNumber);
  return mine.find((r) => r.status === "in_consult") ?? mine.find(canStart) ?? null;
}

// ---- visit timeline (DOC-025) ---------------------------------------------------------------

export type TimelineKind = "ok" | "warn" | "info" | "sync";
export type TimelineEvent = { id: string; label: string; sub?: string; at?: string | null; kind: TimelineKind };

export type TimelineInput = {
  checkedInAt?: string | null;
  vitals: { status: "loading" | "ok" | "none" | "error"; recordedAt?: string | null; summary?: string | null };
  status: string;
  draft: { savedAt: string | null; pendingOnDevice: boolean };
  signedAt: string | null;
  noteSigned: boolean;
  amendments: { id: string; createdAt: string; reason: string }[];
};

const AFTER_CONSULT = ["checkout_pending", "completed", "transferred_out"];

/** Only events the record (or this device) can vouch for; nothing is inferred or invented. */
export function buildTimeline(i: TimelineInput): TimelineEvent[] {
  const out: TimelineEvent[] = [{ id: "in", label: "Checked in", at: i.checkedInAt ?? null, kind: "ok" }];
  if (i.vitals.status === "ok") out.push({ id: "vitals", label: "Vitals recorded", sub: i.vitals.summary ?? undefined, at: i.vitals.recordedAt ?? null, kind: "ok" });
  else if (i.vitals.status === "none") out.push({ id: "vitals", label: "Vitals", sub: "not recorded yet", kind: "warn" });
  else if (i.vitals.status === "error") out.push({ id: "vitals", label: "Vitals", sub: "status unavailable", kind: "warn" });
  if (i.status === "in_consult" || AFTER_CONSULT.includes(i.status) || i.noteSigned) out.push({ id: "consult", label: "Consultation started", kind: "info" });
  if (i.draft.pendingOnDevice) out.push({ id: "draft", label: "Draft saved", sub: "on this device · waiting to sync", kind: "sync" });
  else if (i.draft.savedAt && !i.noteSigned) out.push({ id: "draft", label: "Draft saved", at: i.draft.savedAt, kind: "info" });
  if (i.noteSigned) out.push({ id: "signed", label: "Note signed", at: i.signedAt, kind: "ok" });
  if (i.status === "checkout_pending" || i.status === "completed") out.push({ id: "checkout", label: i.status === "completed" ? "Visit completed" : "Sent to checkout", kind: "ok" });
  if (i.status === "transferred_out") out.push({ id: "transfer", label: "Transferred to another department", kind: "info" });
  for (const a of i.amendments) out.push({ id: `am-${a.id}`, label: "Amendment added", sub: a.reason, at: a.createdAt, kind: "info" });
  return out;
}
