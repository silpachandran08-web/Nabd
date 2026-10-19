// COM-005: package ledger event labels. `expiry_warning_sent` only records that staff marked a reminder;
// no message goes out (real sending: COM-044), so the label must not say "sent".
export function eventLabel(eventType: string, note?: string | null): string {
  if (eventType === "expiry_warning_sent") {
    const tier = /^(\d+)-day/.exec(note ?? "")?.[1];
    return tier ? `Marked as reminded (${tier}-day)` : "Marked as reminded";
  }
  return eventType.replace(/_/g, " ");
}
