// ADM-043: pure helpers of the daily WhatsApp summary dialog (unit-tested without React).

/** The template slots in the order the daily_summary utility template uses them. */
export const SLOT_LABELS = ["Day", "Figures", "Needs attention", "Link"] as const;

/** Form state → PUT /v1/reports/daily-summary/settings body. A sendAt that is not a valid HH:mm falls back to 'close' (the server refuses anything else). */
export function toRequest(s: { enabled: boolean; combined: boolean; recipients: string[]; sendAt: string }) {
  const sendAt = /^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(s.sendAt) ? s.sendAt : "close";
  return { enabled: s.enabled, combined: s.combined, recipients: s.recipients.map((staffId) => ({ staffId })), sendAt };
}
