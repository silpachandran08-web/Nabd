// CLN-012: pure parts of the coded-diagnosis field (no React, no fetch). The server validates every code against the
// active ICD-10 list and generates the note's diagnosis summary; nothing here decides what a valid code is.

export type Icd10Item = { code: string; title: string; favourite: boolean };
/** version and source are null when no ICD-10 list is active for the clinic's region. */
export type Icd10Result = { version: string | null; source: string | null; items: Icd10Item[] };
export type Dx = { code: string; title: string; primary: boolean; qualifier: string | null; listVersion?: string };
export type DxResult = { items: Dx[]; summary: string | null };
export type DxRequest = { items: { code: string; primary: boolean; qualifier?: string }[]; reason?: string };

export const MIN_CHARS = 2;
export const MAX_DIAGNOSES = 20;
export const MAX_QUALIFIER = 200;
export const MIN_REASON = 5;

/** Which label names the list in use: the WHO base list (also the fallback where no regional list is licensed) or a regional one. */
export function sourceKey(source: string | null): "sourceWho" | "sourceAm" | "sourceOther" {
  return source === "WHO" ? "sourceWho" : source === "AM" ? "sourceAm" : "sourceOther";
}

/** Favourites that match while the server search is on its way: every word of the query is in the code or the title. */
export function matchFavourites(favourites: Icd10Item[], query: string, limit = 10): Icd10Item[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return favourites.slice(0, limit);
  return favourites.filter((f) => { const hay = `${f.code} ${f.title}`.toLowerCase(); return words.every((w) => hay.includes(w)); }).slice(0, limit);
}

/** Suggestions never offer a code the visit already has. */
export function withoutPicked(items: Icd10Item[], picked: Dx[]): Icd10Item[] {
  return items.filter((i) => !picked.some((p) => p.code === i.code));
}

/**
 * Adds a code. The first one is the primary diagnosis. Text the doctor had typed as a free-text diagnosis is kept as the
 * first code's qualifier (the generated summary replaces the free text, so it must not be lost).
 */
export function addDx(items: Dx[], pick: { code: string; title: string }, freeText = ""): Dx[] {
  if (items.some((d) => d.code === pick.code) || items.length >= MAX_DIAGNOSES) return items;
  const kept = items.length === 0 ? freeText.trim().slice(0, MAX_QUALIFIER) : "";
  return [...items, { code: pick.code, title: pick.title, primary: items.length === 0, qualifier: kept || null }];
}

/** Removing the primary diagnosis makes the next one primary: a visit with codes always has exactly one. */
export function removeDx(items: Dx[], code: string): Dx[] {
  const left = items.filter((d) => d.code !== code);
  return left.length > 0 && !left.some((d) => d.primary) ? left.map((d, i) => ({ ...d, primary: i === 0 })) : left;
}

export function setPrimary(items: Dx[], code: string): Dx[] {
  return items.some((d) => d.code === code) ? items.map((d) => ({ ...d, primary: d.code === code })) : items;
}

export function setQualifier(items: Dx[], code: string, text: string): Dx[] {
  const q = text.trim().slice(0, MAX_QUALIFIER) || null;
  return items.map((d) => (d.code === code ? { ...d, qualifier: q } : d));
}

export function sameDx(a: Dx[], b: Dx[]): boolean {
  return a.length === b.length && a.every((x) => b.some((y) => y.code === x.code && y.primary === x.primary && (y.qualifier ?? null) === (x.qualifier ?? null)));
}

/** Body of PUT /clinical/visits/{id}/diagnoses. A reason is sent only for a signed note (the change is an amendment). */
export function toRequest(items: Dx[], reason?: string): DxRequest {
  const body: DxRequest = { items: items.map((d) => (d.qualifier ? { code: d.code, primary: d.primary, qualifier: d.qualifier } : { code: d.code, primary: d.primary })) };
  if (reason !== undefined) body.reason = reason.trim();
  return body;
}

/** An amendment needs a changed set and a reason of at least 5 characters (the server rule, CLN-006). */
export function canSaveAmendment(saved: Dx[], draft: Dx[], reason: string): boolean {
  return reason.trim().length >= MIN_REASON && !sameDx(saved, draft);
}

/** What the screen shows: the old free-text field when no list is active and nothing is coded, else the coded field. */
export function fieldMode(a: { load: "loading" | "ok" | "error"; version: string | null; coded: number }): "loading" | "freeText" | "coded" | "error" {
  if (a.load !== "ok") return a.load;
  return a.version === null && a.coded === 0 ? "freeText" : "coded";
}
