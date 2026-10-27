// COM-031/COM-032: pure helpers of the WhatsApp inbox (React-free, unit-tested in tests/unit/s16-com031-inbox.test.mjs).
// The shapes mirror the Comms Gateway inbox DTOs that Core's /v1/inbox/** passes through.

export type Window = { state: "open" | "closed"; closesAt: string | null };
export type Thread = {
  id: string;
  patientRef?: string | null;
  displayName: string;
  lastMessagePreview: string;
  unread: number;
  waitingMinutes: number;
  window: Window;
  mode: "bot" | "human";
  assignedTo?: string | null;
  assignedToName?: string | null;
  optedOut: boolean;
  clinical: boolean;
  clinicalSuggested: boolean;
  language?: string | null;
};
export type Message = {
  id: string;
  direction: "in" | "out";
  author: "patient" | "staff" | "template" | "bot";
  text: string | null;
  templateName: string | null;
  status: string | null;
  at: string;
  authorStaffRef?: string | null;
};

export const FILTERS = [
  { id: "all", label: "All" },
  { id: "unread", label: "Unread" },
  { id: "needs_reply", label: "Needs reply" },
  { id: "bot", label: "Bot" },
] as const;
export type FilterId = (typeof FILTERS)[number]["id"];

export const CLOSING_SOON_MS = 4 * 3600_000;
export const PREVIEW_MAX = 60;
export const CLINICAL_PREVIEW = "Clinical message";

export type Pill = { kind: "open" | "closing" | "template" | "optedout"; label: string };

function span(ms: number): string {
  const min = Math.max(0, Math.floor(ms / 60000));
  return min >= 60 ? `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, "0")} min` : `${min} min`;
}

/** Open / Closing (amber, under 4 h, with the countdown) / Template only / Opted out. `nowMs` is ticked each minute by the screen. */
export function sessionPill(t: Pick<Thread, "window" | "optedOut">, nowMs: number): Pill {
  if (t.optedOut) return { kind: "optedout", label: "Opted out" };
  const closes = t.window.closesAt ? Date.parse(t.window.closesAt) : NaN;
  if (t.window.state !== "open" || Number.isNaN(closes) || closes <= nowMs) return { kind: "template", label: "Template only" };
  const left = closes - nowMs;
  return left < CLOSING_SOON_MS ? { kind: "closing", label: `Closes in ${span(left)}` } : { kind: "open", label: "Open" };
}

/** Free text is allowed only in an open (or closing) window of a patient who has not opted out. */
export const canFreeText = (t: Pick<Thread, "window" | "optedOut">, nowMs: number): boolean => {
  const k = sessionPill(t, nowMs).kind;
  return k === "open" || k === "closing";
};

export function waitingChip(minutes: number | null | undefined): string {
  const m = minutes ?? 0;
  if (m < 1) return "";
  return m >= 60 ? `Waiting ${Math.floor(m / 60)} h ${m % 60} min` : `Waiting ${m} min`;
}

/** Lock-screen safe: a clinical thread never shows its text, everything else is cut at 60 characters. */
export function previewText(t: Pick<Thread, "clinical" | "lastMessagePreview">): string {
  if (t.clinical) return CLINICAL_PREVIEW;
  const p = t.lastMessagePreview ?? "";
  return p.length <= PREVIEW_MAX ? p : `${p.slice(0, PREVIEW_MAX - 1)}…`;
}

/** The thread to open first: the one waiting longest; with nobody waiting, the first in the list. */
export function oldestWaiting(threads: readonly Thread[]): Thread | null {
  let best: Thread | null = null;
  for (const t of threads) if (t.waitingMinutes > 0 && (!best || t.waitingMinutes > best.waitingMinutes)) best = t;
  return best ?? threads[0] ?? null;
}

/** Who a bubble belongs to: the patient, the bot, a staff member, or the template that went out. */
export function authorLabel(m: Pick<Message, "author" | "templateName" | "authorStaffRef">, meId: string | null, staffName?: (id: string) => string | undefined): string {
  if (m.author === "patient") return "Patient";
  if (m.author === "bot") return "Nabd assistant - automated";
  if (m.author === "template") return `Template: ${m.templateName ?? "message"}`;
  if (m.authorStaffRef && m.authorStaffRef === meId) return "You";
  return (m.authorStaffRef && staffName?.(m.authorStaffRef)) || "Staff";
}

export type Tick = { glyph: string; label: string };
/** Delivery ticks on a message we sent. */
export function tick(status: string | null | undefined): Tick {
  switch (status) {
    case "sent": return { glyph: "✓", label: "Sent" };
    case "delivered": return { glyph: "✓✓", label: "Delivered" };
    case "read": return { glyph: "✓✓", label: "Read" };
    case "failed": case "blocked": case "expired": case "cancelled": return { glyph: "!", label: "Not delivered" };
    default: return { glyph: "…", label: "Sending" };
  }
}
export const isFailed = (status: string | null | undefined): boolean => status === "failed" || status === "blocked" || status === "expired";

/** The same two checks the server runs (COM-032): they only warn, a person decides. */
const RESULT_OR_DIAGNOSIS = /\b(diagnos(is|ed)|test results?|lab results?|report shows|biopsy|hba1c|you (have|tested) (positive|negative)|(positive|negative) for)\b|\b\d+(\.\d+)?\s?(mg\/dl|mmol\/l|g\/dl|iu\/l)\b/i;
export const looksLikeResultOrDiagnosis = (text: string): boolean => RESULT_OR_DIAGNOSIS.test(text);

/** "{{1}}", "{{2}}" in a template body -> how many variable fields the picker shows. */
export function variableCount(body: string): number {
  const nums = [...body.matchAll(/\{\{(\d+)\}\}/g)].map((m) => Number(m[1]));
  return nums.length ? Math.max(...nums) : 0;
}
export function fillTemplate(body: string, params: readonly string[]): string {
  return body.replace(/\{\{(\d+)\}\}/g, (_, n) => params[Number(n) - 1] || `{{${n}}}`);
}

export type KeyAction = "next" | "prev" | "reply" | "templates" | null;
/** J/K move through the list, R reply, T templates. Ignored in form fields, with a modifier, or while a dialog is open. */
export function keyAction(e: { key: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean; target?: { tagName?: string; isContentEditable?: boolean } | null }, dialogOpen: boolean): KeyAction {
  if (dialogOpen || e.ctrlKey || e.metaKey || e.altKey) return null;
  const tag = e.target?.tagName?.toLowerCase();
  if (tag === "input" || tag === "textarea" || tag === "select" || e.target?.isContentEditable) return null;
  switch (e.key.toLowerCase()) {
    case "j": return "next";
    case "k": return "prev";
    case "r": return "reply";
    case "t": return "templates";
    default: return null;
  }
}

export const NOTE_OUTCOMES = [
  { code: "reached", label: "Reached the patient" },
  { code: "no_answer", label: "No answer" },
  { code: "call_back", label: "Call back later" },
  { code: "booked", label: "Booked" },
] as const;
