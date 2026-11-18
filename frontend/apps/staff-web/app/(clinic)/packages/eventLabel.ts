// Package ledger event labels. COM-044: messages are real sends, so the label says what was asked for and deliveryLabel says
// what happened to it (the tick); a blocked send never reads as "sent".
const MESSAGE_EVENTS: Record<string, string> = {
  card_requested: "Package card",
  session_nudge: "Next session reminder",
  grace_notice: "Grace period notice",
};

export function eventLabel(eventType: string, note?: string | null): string {
  if (eventType === "expiry_warning_sent") {
    const tier = /^(\d+)-day/.exec(note ?? "")?.[1];
    return tier ? `Expiry reminder (${tier}-day)` : "Expiry reminder";
  }
  return MESSAGE_EVENTS[eventType] ?? eventType.replace(/_/g, " ");
}

const DELIVERY: Record<string, string> = {
  requested: "✓ Sent",
  delivered: "✓✓ Delivered",
  read: "✓✓ Read",
  blocked: "Not sent: no messaging consent",
  failed: "Failed",
  cancelled: "Cancelled",
  expired: "Not sent: window passed",
};

/** The delivery tick of a message event; null for events that are not messages. */
export function deliveryLabel(status?: string | null): string | null {
  return status ? DELIVERY[status] ?? status : null;
}
