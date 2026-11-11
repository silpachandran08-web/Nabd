// COM-043: the reminder cascade state of a booking as the desk sees it (calendar cell and booking details). Pure (unit-tested).
export type ConfirmationState = "none" | "nudged" | "confirmed" | "unconfirmed_flagged";

export type Chip = { label: string; tone: "ok" | "warn" | "danger" };

const CHIPS: Record<string, Chip> = {
  confirmed: { label: "Confirmed by patient", tone: "ok" },
  nudged: { label: "Asked again to confirm", tone: "warn" },
  unconfirmed_flagged: { label: "Unconfirmed", tone: "danger" },
};

/** Only an appointment that has not arrived carries a chip; "none" (no answer needed yet) shows nothing. */
export function confirmationChip(state: string | null | undefined, status: string): Chip | null {
  if (status !== "scheduled" || !state) return null;
  return CHIPS[state] ?? null;
}
