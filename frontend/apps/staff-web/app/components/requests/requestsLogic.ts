// ACC-010: pure helpers of the booking-requests inbox (React-free, unit-tested in tests/unit/s15-acc010-requests.test.mjs).

export const REASON_LABEL: Record<string, string> = {
  new_patient: "New patient",
  over_cap: "Daily auto-accept limit reached",
  triage_flag: "Triage words in the booking",
  procedure: "Procedure visit",
  group: "Group or sibling booking",
  policy_review: "Clinic reviews every booking",
};
export const reasonLabel = (code: string | null | undefined): string => (code && REASON_LABEL[code]) || "Needs review";

/** Coded decline reasons (ACC-007 will supply the clinic's list; these are administrative, never clinical). */
export const DECLINE_REASONS: { code: string; label: string }[] = [
  { code: "doctor_unavailable", label: "Doctor not available" },
  { code: "no_suitable_time", label: "No suitable time" },
  { code: "service_not_offered", label: "Service not offered here" },
  { code: "please_call_clinic", label: "Please call the clinic" },
  { code: "other", label: "Other reason" },
];

export type SlaState = { overdue: boolean; label: string };
/** "8 min left" / "Overdue 3 min"; under a minute reads "under 1 min". */
export function slaState(slaDueAt: string | null | undefined, nowMs: number): SlaState {
  const due = slaDueAt ? Date.parse(slaDueAt) : NaN;
  if (Number.isNaN(due)) return { overdue: false, label: "" };
  const min = Math.floor(Math.abs(due - nowMs) / 60000);
  const text = min < 1 ? "under 1 min" : `${min} min`;
  return due - nowMs >= 0 ? { overdue: false, label: `${text} left` } : { overdue: true, label: `Overdue ${text}` };
}

function parts(ts: number, tz: string): number {
  const f = new Intl.DateTimeFormat("en-US", { timeZone: tz, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
  const p = Object.fromEntries(f.formatToParts(new Date(ts)).map((x) => [x.type, x.value]));
  return Date.UTC(+p.year, +p.month - 1, +p.day, +p.hour, +p.minute, +p.second);
}
const offsetMs = (ts: number, tz: string): number => parts(Math.floor(ts / 1000) * 1000, tz) - Math.floor(ts / 1000) * 1000;

/** "2026-11-02T10:15" read as clinic wall time in `tz` -> ISO instant. */
export function zonedToInstant(local: string, tz: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local);
  if (!m) return null;
  const asUtc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]);
  let ts = asUtc - offsetMs(asUtc, tz);
  ts = asUtc - offsetMs(ts, tz); // second pass settles a DST edge
  return new Date(ts).toISOString();
}

/** ISO instant -> "YYYY-MM-DDTHH:mm" in `tz`, for a datetime-local input. */
export function instantToZoned(iso: string, tz: string): string {
  const d = new Date(parts(Date.parse(iso), tz)).toISOString();
  return d.slice(0, 16);
}

export type KeyAction = "next" | "prev" | "accept" | "suggest" | "decline" | null;
/** Inbox shortcuts: J/K move, A accept, S suggest, D decline. Ignored in form fields, with a modifier, or while a dialog is open. */
export function keyAction(e: { key: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean; target?: { tagName?: string; isContentEditable?: boolean } | null }, dialogOpen: boolean): KeyAction {
  if (dialogOpen || e.ctrlKey || e.metaKey || e.altKey) return null;
  const tag = e.target?.tagName?.toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select" || e.target?.isContentEditable) return null;
  switch (e.key.toLowerCase()) {
    case "j": return "next";
    case "k": return "prev";
    case "a": return "accept";
    case "s": return "suggest";
    case "d": return "decline";
    default: return null;
  }
}
