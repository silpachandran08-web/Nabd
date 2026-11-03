// PHL-010: pure rules of the stock-take sheet and the batch adjustment overlay (unit-tested). The server owns the truth: it
// recomputes every variance from the snapshot plus the movements up to the count, and enforces the second sign-off.

/** The fixed reason list (FRD PHR-04); "other" needs a note. */
export const REASONS = [
  { code: "count_correction", label: "Count correction" },
  { code: "damaged", label: "Damaged" },
  { code: "theft_loss", label: "Theft / loss" },
  { code: "expired", label: "Expired" },
  { code: "other", label: "Other (note required)" },
] as const;
export type ReasonCode = (typeof REASONS)[number]["code"];

export type SheetLine = { batchId: string; location?: string | null; expectedQty: number; controlled: boolean };
export type Draft = { counted: string; reasonCode: ReasonCode | ""; note: string };

/** A whole number >= 0, else null (blank = not counted yet). */
export function parseCount(text: string): number | null {
  const t = text.trim();
  return /^\d{1,8}$/.test(t) ? Number(t) : null;
}

/** counted - expected, or null while the line has no valid count. */
export function variance(line: SheetLine, draft: Draft | undefined): number | null {
  const n = draft ? parseCount(draft.counted) : null;
  return n == null ? null : n - line.expectedQty;
}

/** Lines grouped by shelf, in sheet order; a batch without a location goes under "No location". */
export function byLocation<T extends SheetLine>(lines: T[]): { location: string; lines: T[] }[] {
  const out: { location: string; lines: T[] }[] = [];
  for (const l of lines) {
    const loc = l.location?.trim() || "No location";
    const g = out.find((x) => x.location === loc);
    if (g) g.lines.push(l); else out.push({ location: loc, lines: [l] });
  }
  return out;
}

/** Posting needs the second signer when any controlled line has a non-zero variance. */
export function needsApproval(lines: SheetLine[], variances: (line: SheetLine) => number | null): boolean {
  return lines.some((l) => l.controlled && (variances(l) ?? 0) !== 0);
}

/** A reduction of a controlled drug is a loss: the screen shows the neutral regulator-report reminder. */
export const lossReminder = (controlled: boolean, delta: number | null): boolean => controlled && delta != null && delta < 0;

// Wording is neutral on purpose (names no regulation or form): the exact text needs compliance sign-off.
export const LOSS_REMINDER = "This is a loss of a controlled drug. Check whether a report to the regulator is due.";

/** Message for the first problem of a draft, or null when it can be sent. */
export function draftProblem(d: Draft): string | null {
  if (d.counted.trim() !== "" && parseCount(d.counted) == null) return "Enter the counted quantity as a whole number.";
  if (d.reasonCode === "other" && d.note.trim() === "") return "Reason \"Other\" needs a note.";
  return null;
}
