// ADM-014: pure rules of the paper-card quick entry (tests/unit/adm014-paper-entry.test.mjs); the component only renders these.
// Field names are the ones POST /v1/patients uses, so a server field error lands on the same input.
export type Card = { name: string; phone: string; yearOfBirth: string; gender: string; note: string };
export type CardField = "name" | "phone" | "yearOfBirth" | "gender";
export type CardIssue = "required" | "phoneShort" | "yearInvalid" | "yearMinor";
export const BLANK: Card = { name: "", phone: "", yearOfBirth: "", gender: "", note: "" };
export const FIELD_ORDER: CardField[] = ["name", "phone", "yearOfBirth", "gender"];

/** Wait after the last key before asking. The story allows 300 ms from typing to warning: 120 ms here leaves 180 ms for the lookup. */
export const LOOKUP_DEBOUNCE_MS = 120;
const MIN_DIGITS = 8; // shorter than any mobile we serve: nothing is asked before this

/** The digits that identify the number as typed (no spaces, no leading trunk or 00 zeros); "" while too short to look up. */
export function lookupDigits(phone: string): string {
  const d = phone.replace(/\D/g, "").replace(/^0+/, "");
  return d.length >= MIN_DIGITS ? d : "";
}

/** What must be fixed before the card can be saved. A year that could still mean under 18 needs the full registration (guardian). */
export function cardIssues(c: Card, thisYear: number): Partial<Record<CardField, CardIssue>> {
  const out: Partial<Record<CardField, CardIssue>> = {};
  if (!c.name.trim()) out.name = "required";
  if (!c.phone.trim()) out.phone = "required";
  else if (!lookupDigits(c.phone)) out.phone = "phoneShort";
  const y = c.yearOfBirth.trim();
  if (!y) out.yearOfBirth = "required";
  else if (!/^\d{4}$/.test(y) || Number(y) < 1900 || Number(y) > thisYear) out.yearOfBirth = "yearInvalid";
  else if (thisYear - Number(y) <= 18) out.yearOfBirth = "yearMinor";
  if (!["male", "female", "other"].includes(c.gender)) out.gender = "required";
  return out;
}

/** The request body for a card that passed cardIssues. `newPerson` = staff said this is someone else on the same number. */
export function cardBody(c: Card, newPerson: boolean) {
  const note = c.note.trim();
  return {
    name: c.name.trim(), phone: c.phone.trim(), yearOfBirth: Number(c.yearOfBirth.trim()), gender: c.gender, source: "paper_import" as const,
    ...(note ? { note } : {}), ...(newPerson ? { confirmedNotDuplicate: true } : {}),
  };
}

export type Hit = { id: string; name: string; phone: string; dob?: string | null };
/** The last answer for `digits`. `fromSave`: the server's own 409 candidates, shown whatever their number. */
export type Lookup = { digits: string; hits: Hit[]; failed?: boolean; fromSave?: boolean } | null;
export type Pill = "idle" | "checking" | "clear" | "match" | "new" | "failed";

/** Patients whose stored number is the typed one (the search itself answers by prefix, so a longer number is not a match). */
export function matches(lookup: Lookup, digits: string): Hit[] {
  if (!lookup || lookup.digits !== digits || !digits) return [];
  return lookup.fromSave ? lookup.hits : lookup.hits.filter((h) => h.phone.replace(/\D/g, "").endsWith(digits));
}

/** What the duplicate pill shows for the number as typed now. An answer or a "new person" choice for other digits is stale. */
export function pillState(phone: string, lookup: Lookup, newPersonFor: string | null): Pill {
  const d = lookupDigits(phone);
  if (!d) return "idle";
  if (!lookup || lookup.digits !== d) return "checking";
  if (lookup.failed) return "failed";
  if (matches(lookup, d).length === 0) return "clear";
  return newPersonFor === d ? "new" : "match";
}

export const birthYear = (dob?: string | null): string => (dob && /^\d{4}/.test(dob) ? dob.slice(0, 4) : "");

/** Calls `run` with the last pushed value once `ms` pass without another push; cancel() drops what is pending. */
export function debouncer<T>(run: (value: T) => void, ms: number) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    push(value: T) { clearTimeout(timer); timer = setTimeout(() => run(value), ms); },
    cancel() { clearTimeout(timer); },
  };
}

/**
 * Keys per card beyond the characters on the card itself: Tab after name, mobile and year, one letter for sex, Enter to save.
 * The 100-cards-an-hour target is 36 s a card; this is the part of it the form adds.
 */
export const CONTROL_KEYS_PER_CARD = 5;
