// ACC-008 / ACC-013: booking-policy.v1 on the client: types, the built-in default (same values as the server's
// BookingPresets.defaults), the "unpublished changes" test and the patient-journey preview, all computed from the draft.

export type VisitTypeKey = "new" | "followUp" | "procedure" | "teleconsult";
export type Acceptance = "auto" | "review" | "hybrid";
export type VisitRule = { acceptance: Acceptance; dailyCapPerDoctor: number; reviewerRole: "doctor" | "reception" | "nurse" | "owner" };
export type BookingPolicy = {
  preset: string | null; presetCustomised?: boolean; solo: boolean;
  visitTypes: Record<VisitTypeKey, VisitRule>;
  checkIn: { modes: string[] };
  eta: { displayMode: "exact" | "range" | "queue_position"; leaveHomeMarginMinutes: number };
  walkIn: { interleave: "1:2" | "1:3" | "1:4" | "reserved_only" | "none" };
  noShow: { graceMinutes: 10 | 15 | 20; autoMark: boolean; pullForward: boolean; depositAfterRepeat: boolean };
  workflow: { vitalsEnabled: boolean; triageEnabled: boolean };
};

export const VISIT_TYPES: { key: VisitTypeKey; label: string }[] = [
  { key: "new", label: "New patient" }, { key: "followUp", label: "Follow-up" },
  { key: "procedure", label: "Procedure / package" }, { key: "teleconsult", label: "Teleconsult" },
];
export const CHECK_IN_MODES: { key: string; label: string }[] = [
  { key: "door_qr", label: "Door QR" }, { key: "whatsapp", label: "WhatsApp \"I'm here\"" }, { key: "geofence", label: "Geofence" }, { key: "desk", label: "Reception desk" },
];
const SELF_CHECK_IN = ["door_qr", "whatsapp", "geofence"];

export function defaultPolicy(): BookingPolicy {
  const rule = (): VisitRule => ({ acceptance: "auto", dailyCapPerDoctor: 200, reviewerRole: "reception" });
  return {
    preset: null, solo: false,
    visitTypes: { new: rule(), followUp: rule(), procedure: rule(), teleconsult: rule() },
    checkIn: { modes: ["desk", "door_qr", "whatsapp"] },
    eta: { displayMode: "range", leaveHomeMarginMinutes: 0 },
    walkIn: { interleave: "none" },
    noShow: { graceMinutes: 15, autoMark: false, pullForward: false, depositAfterRepeat: false },
    workflow: { vitalsEnabled: true, triageEnabled: true },
  };
}

/** Deep-equal on the settings, ignoring the server-owned presetCustomised label. */
export function samePolicy(a: BookingPolicy | null | undefined, b: BookingPolicy | null | undefined): boolean {
  const strip = (p: BookingPolicy | null | undefined) => JSON.stringify(p ? { ...p, presetCustomised: undefined } : null);
  return strip(a) === strip(b);
}

/** The first client-side refusal, mirroring the server's cross-field rules; the server stays the authority. */
export function policyProblem(p: BookingPolicy): string | null {
  if (p.checkIn.modes.length === 0) return "Choose at least one check-in mode.";
  if (p.solo && !p.checkIn.modes.some((m) => SELF_CHECK_IN.includes(m))) return "Solo mode needs a self check-in mode (door QR, WhatsApp or geofence); reception-only is not allowed.";
  if (p.eta.leaveHomeMarginMinutes > 0 && p.eta.displayMode === "queue_position") return "A leave-home margin needs an exact or range time estimate.";
  for (const v of VISIT_TYPES) {
    const c = p.visitTypes[v.key].dailyCapPerDoctor;
    if (!Number.isInteger(c) || c < 1 || c > 200) return `${v.label}: the daily cap must be a whole number from 1 to 200.`;
  }
  return null;
}

export type JourneyStep = { key: string; label: string; touch: string | null };

/** What the patient goes through before the consult and who on the staff has to touch it (touch = the role, null = nobody). */
export function journey(p: BookingPolicy, visit: VisitTypeKey): { steps: JourneyStep[]; touchesBeforeConsult: number } {
  const rule = p.visitTypes[visit];
  const reviewer = p.solo ? "doctor" : rule.reviewerRole;
  const steps: JourneyStep[] = [];
  steps.push(rule.acceptance === "auto"
    ? { key: "booking", label: `Booking accepted automatically (up to ${rule.dailyCapPerDoctor} a day per doctor)`, touch: null }
    : rule.acceptance === "review"
      ? { key: "booking", label: `Booking waits for the ${reviewer} to accept`, touch: reviewer }
      : { key: "booking", label: `Booking accepted automatically for returning patients; the ${reviewer} reviews patients new to the doctor`, touch: visit === "new" ? reviewer : null });
  steps.push({ key: "eta", label: `Patient sees ${p.eta.displayMode === "exact" ? "an exact time" : p.eta.displayMode === "range" ? "a time range" : "their queue position"}${p.eta.leaveHomeMarginMinutes > 0 ? `, told to leave ${p.eta.leaveHomeMarginMinutes} min ahead` : ""}`, touch: null });
  const self = p.checkIn.modes.some((m) => SELF_CHECK_IN.includes(m));
  steps.push(self ? { key: "checkin", label: "Patient checks in on their own", touch: null } : { key: "checkin", label: "Reception checks the patient in", touch: "reception" });
  if (p.workflow.triageEnabled) steps.push({ key: "triage", label: "Triage", touch: "nurse" });
  if (p.workflow.vitalsEnabled) steps.push({ key: "vitals", label: "Vitals", touch: "nurse" });
  return { steps, touchesBeforeConsult: steps.filter((s) => s.touch).length };
}

export function setIn<T>(obj: T, path: (string | number)[], value: unknown): T {
  if (path.length === 0) return value as T;
  const [head, ...rest] = path;
  const base = (obj ?? {}) as Record<string, unknown>;
  return { ...base, [head]: setIn(base[head as string], rest, value) } as T;
}

/** "/visitTypes/followUp/dailyCapPerDoctor" -> "Follow-up: daily cap" for the diff and error lists. */
const LEAF: Record<string, string> = {
  acceptance: "acceptance", dailyCapPerDoctor: "daily cap", reviewerRole: "reviewer", modes: "check-in modes", displayMode: "ETA display",
  leaveHomeMarginMinutes: "leave-home margin", interleave: "walk-in interleave", graceMinutes: "no-show grace", autoMark: "auto-mark no-show",
  pullForward: "pull forward", depositAfterRepeat: "deposit flag", vitalsEnabled: "vitals step", triageEnabled: "triage", solo: "solo mode", preset: "preset", presetCustomised: "customised",
};
export function pointerLabel(pointer: string): string {
  const parts = pointer.split("/").filter(Boolean);
  const leaf = LEAF[parts[parts.length - 1]] ?? parts[parts.length - 1] ?? "policy";
  const visit = parts[0] === "visitTypes" ? VISIT_TYPES.find((v) => v.key === parts[1])?.label : null;
  return visit ? `${visit}: ${leaf}` : leaf;
}

export function showValue(v: unknown): string {
  if (v === null || v === undefined) return "none";
  if (typeof v === "boolean") return v ? "on" : "off";
  if (Array.isArray(v)) return v.join(", ");
  return String(v);
}
