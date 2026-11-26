// PLT-047: pure helpers for the owner's privacy-request queue (unit-tested in tests/unit/s27-plt047-privacy-queue.test.mjs).
export type DsrItem = {
  requestId: string; patientId: string; patientName: string; mrn: string | null; type: "access" | "correction" | "erasure";
  status: "received" | "verifying" | "in_progress" | "awaiting_notice" | "completed" | "rejected" | "extended";
  channel: "whatsapp" | "web" | "desk"; requesterType: "patient" | "caregiver" | "desk"; receivedAt: string; dueAt: string;
  completedAt: string | null; dueSoon: boolean; overdue: boolean; reminded: boolean; lastEvent: string | null;
};

export type Countdown = { tone: "danger" | "warn" | "ok" | "neutral"; key: "closed" | "overdue" | "dueSoon" | "daysLeft"; days: number };

const DAY_MS = 24 * 60 * 60 * 1000;
const CLOSED = new Set(["completed", "rejected"]);

/** The chip of one request. The server decides overdue and due soon (its clock, 75% of the period); the day count is for the label only. */
export function countdown(item: Pick<DsrItem, "status" | "dueAt" | "dueSoon" | "overdue">, now: number): Countdown {
  if (CLOSED.has(item.status)) return { tone: "neutral", key: "closed", days: 0 };
  const left = Date.parse(item.dueAt) - now;
  if (item.overdue || left <= 0) return { tone: "danger", key: "overdue", days: Math.max(1, Math.floor(-left / DAY_MS)) };
  const days = Math.max(1, Math.ceil(left / DAY_MS));
  return item.dueSoon ? { tone: "warn", key: "dueSoon", days } : { tone: "ok", key: "daysLeft", days };
}

/** i18n key (common.privacyRequests.*) for a failed load, by HTTP status (0 = no answer). */
export function loadProblem(status: number): "forbidden" | "loadError" {
  return status === 403 ? "forbidden" : "loadError";
}

export const FILTERS = ["open", "all"] as const;
export type Filter = (typeof FILTERS)[number];

/** The query string of one page: the open filter is the server's "open" status; "all" sends no status. */
export function queueQuery(filter: Filter, type: DsrItem["type"] | "", cursor?: string | null): string {
  const q = new URLSearchParams();
  if (filter === "open") q.set("status", "open");
  if (type) q.set("type", type);
  if (cursor) q.set("cursor", cursor);
  const s = q.toString();
  return s ? `?${s}` : "";
}
