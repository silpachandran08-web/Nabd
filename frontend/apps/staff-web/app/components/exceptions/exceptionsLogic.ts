// ACC-032: pure helpers of the front desk's exceptions inbox (React-free, unit-tested in tests/unit/s16-acc032-exceptions.test.mjs).
// An item carries ids and codes only; the sentence is built here from the kind and its params, never stored text.

export type DeskItem = {
  id: string; kind: string; severity: "info" | "warn" | "urgent"; refType: string; refId: string; summary: string;
  params: Record<string, unknown>; actions: string[]; createdAt: string;
};

export const KIND_TITLE: Record<string, string> = {
  checkin_failed: "Self check-in needs you",
  unconfirmed_booking: "Unconfirmed",
  request_sla: "Request about to breach",
  checkout_unpaid: "Unpaid visit",
  handoff: "Patient needs a person",
};

/** One plain sentence per item: what happened and what the desk can do. */
export function headline(item: Pick<DeskItem, "kind" | "params">): string {
  const p = item.params ?? {};
  switch (item.kind) {
    case "checkin_failed": {
      if (p.reason === "outside_geofence") {
        const m = typeof p.distanceM === "number" ? ` (${distanceText(p.distanceM)} away)` : "";
        return `A patient tried to check in themselves but is not at the clinic${m}.`;
      }
      if (p.reason === "geofence_not_set") return "A patient tried to check in themselves, but the clinic location is not set.";
      return "A patient tried to check in themselves and has no appointment today.";
    }
    case "unconfirmed_booking": return "This booking starts within 2 hours and the patient has not confirmed.";
    case "request_sla": return "A booking request is about to run past its reply time.";
    case "checkout_unpaid": return "This visit has been waiting at checkout for a while.";
    case "handoff": return "A patient asked for a person in WhatsApp.";
    default: return "Needs a person.";
  }
}

export function distanceText(m: number): string {
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${Math.round(m)} m`;
}

const RANK: Record<string, number> = { urgent: 0, warn: 1, info: 2 };

/** Urgent first, then oldest first. */
export function sortItems(items: DeskItem[]): DeskItem[] {
  return [...items].sort((a, b) => (RANK[a.severity] ?? 3) - (RANK[b.severity] ?? 3) || Date.parse(a.createdAt) - Date.parse(b.createdAt));
}

export const ACTION_LABEL: Record<string, string> = {
  confirm_arrival: "Confirm arrival", ask_patient: "Ask patient", call: "Call", message: "Message",
  review_request: "Review request", open_checkout: "Open checkout", open_inbox: "Open inbox", resolve: "Done", snooze: "Snooze 30 min",
};

/** Where a link-style action goes; null = the action is a button the hook handles. */
export function actionHref(item: Pick<DeskItem, "kind" | "refId">, action: string): string | null {
  switch (action) {
    case "ask_patient": case "open_inbox": return "/messages";
    case "review_request": return "/requests";
    case "open_checkout": return `/checkout/${item.refId}`;
    default: return null;
  }
}

/** The grant each button needs; links need only the screen's own gate. */
export function actionPermission(action: string): string | null {
  if (action === "confirm_arrival") return "queue:create";
  if (action === "resolve" || action === "snooze") return "queue:edit";
  return null;
}

/** Item ids that left the list since the previous load (for the "resolved" announcement). */
export function removedIds(prev: DeskItem[], next: DeskItem[]): string[] {
  const keep = new Set(next.map((i) => i.id));
  return prev.filter((i) => !keep.has(i.id)).map((i) => i.id);
}
