// DOC-033: pure helpers for the structured prescription pad. No clinical content lives here: only arithmetic on
// what the doctor entered (a morning-noon-night pattern and a duration in days or weeks).

/** Dose-per-slot choices for the sig picker. "½" and "1½" are the usual written forms. */
export const SIG_STEPS = ["0", "½", "1", "1½", "2", "3", "4"] as const;
export type SigSlots = [string, string, string]; // morning, noon, night

/** Generic routes for the picker. A fixed UI list; the server stores whatever was chosen and does not interpret it. */
export const ROUTES = [
  "Oral", "Sublingual", "Topical", "Inhalation", "Nasal", "Ophthalmic", "Otic",
  "Intramuscular", "Intravenous", "Subcutaneous", "Rectal", "Vaginal", "Transdermal",
] as const;

const STEP_VALUE: Record<string, number> = { "0": 0, "½": 0.5, "1": 1, "1½": 1.5, "2": 2, "3": 3, "4": 4 };

export function parseSig(pattern: string | null | undefined): SigSlots | null {
  if (!pattern) return null;
  const parts = pattern.trim().split("-");
  if (parts.length !== 3 || !parts.every((p) => p in STEP_VALUE)) return null;
  return [parts[0], parts[1], parts[2]];
}

export function formatSig(slots: SigSlots): string {
  return slots.join("-");
}

/** Units taken per day, or null when the pattern is absent or not a recognised pattern. */
export function unitsPerDay(pattern: string | null | undefined): number | null {
  const slots = parseSig(pattern);
  if (!slots) return null;
  const n = slots.reduce((sum, p) => sum + STEP_VALUE[p], 0);
  return n > 0 ? n : null;
}

/** Whole days from text like "5 days", "1 week", "2wk", "7d". Months and bare numbers are NOT guessed (null). */
export function durationDays(text: string | null | undefined): number | null {
  const m = /^\s*(\d{1,3})\s*(d|day|days|w|wk|wks|week|weeks)\s*$/i.exec(text ?? "");
  if (!m) return null;
  const n = parseInt(m[1], 10);
  if (n <= 0) return null;
  return /^w/i.test(m[2]) ? n * 7 : n;
}

export type QtySuggestion = { quantity: number; perDay: number; days: number };

/** sig x duration. Null whenever either side is unknown: nothing is guessed. */
export function suggestQuantity(sigPattern: string | null | undefined, duration: string | null | undefined): QtySuggestion | null {
  const perDay = unitsPerDay(sigPattern);
  const days = durationDays(duration);
  if (perDay === null || days === null) return null;
  const quantity = Math.round(perDay * days * 100) / 100;
  return quantity > 0 && quantity <= 999999 ? { quantity, perDay, days } : null;
}

/** "0.5" style numbers for the UI, no trailing zeros. */
export function formatQty(n: number): string {
  return String(Math.round(n * 100) / 100);
}
