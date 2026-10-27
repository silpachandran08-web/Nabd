// ADM-032: licence register helpers shared by the setup screen (no React, no fetch).
export const LICENCE_KINDS = ["clinician", "facility", "nurse", "lab", "pharmacist"] as const;
export type LicenceKind = (typeof LICENCE_KINDS)[number];

// Issuing bodies the story names per market; a market without an entry takes free text. The backend does not restrict the value.
export const ISSUING_BODIES: Record<string, readonly string[]> = { AE: ["DHA", "DOH", "MOHAP"], SA: ["SCFHS", "MOH"] };

export const REMINDER_THRESHOLDS = [60, 30, 7] as const;

/** Whole days from today (YYYY-MM-DD, the clinic's day) to expiry; negative once expired. */
export function daysLeft(expiry: string, today: string): number {
  return Math.round((Date.parse(`${expiry}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86_400_000);
}

/** The reminder thresholds already sent, as text for a pill: "60d, 30d". */
export function remindersText(sent: readonly number[] | null | undefined): string {
  return [...(sent ?? [])].sort((a, b) => b - a).map((d) => `${d}d`).join(", ");
}
