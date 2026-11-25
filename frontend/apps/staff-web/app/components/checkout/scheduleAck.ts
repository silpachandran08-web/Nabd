// PHL-023: Schedule H1 / X at the counter. Pure rules (unit-tested): which cart lines still need the seller's second
// confirmation, when the "no registered pharmacist on duty" banner shows, and which line the server refused.
// The classes come from the server (GET /pharmacy/counter-rules, the market's schedule rules); nothing here decides them,
// and the server checks every sale again (422 schedule-ack-required).

export type CounterRules = { pharmacistOnDuty: boolean; ackClasses: string[]; pharmacistWarningClasses: string[] };
type Line = { item: { scheduleClass: string }; ackReason?: string };

export const ACK_MIN = 3;
export const ACK_MAX = 200;

/** The reason as it is sent (trimmed), or null when it is not one: the server asks for 3 to 200 characters. */
export function ackReason(text: string): string | null {
  const r = text.trim();
  return r.length >= ACK_MIN && r.length <= ACK_MAX ? r : null;
}

/** A line whose schedule class asks for the second confirmation and has none yet: payment stays off until it has one. */
export const needsAck = (l: Line, rules: CounterRules): boolean => rules.ackClasses.includes(l.item.scheduleClass) && !l.ackReason;

/** The banner: nobody registered as a pharmacist is on duty and the cart holds a line of a class that warns about it. */
export const pharmacistWarning = (cart: readonly Line[], rules: CounterRules): boolean =>
  !rules.pharmacistOnDuty && cart.some((l) => rules.pharmacistWarningClasses.includes(l.item.scheduleClass));

/** The charge code of the line a sale was refused for (missing confirmation), or null for any other problem. */
export function ackRefusedCode(p: { type?: string; chargeCode?: unknown } | null | undefined): string | null {
  return p?.type?.split("/").pop() === "schedule-ack-required" && typeof p.chargeCode === "string" ? p.chargeCode : null;
}
