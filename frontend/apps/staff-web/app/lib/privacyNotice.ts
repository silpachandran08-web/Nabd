// IDC-003: pure helpers for the privacy notice screens (no React, unit-tested in tests/unit/s14-idc003-notice.test.mjs).
export type NoticeVersion = {
  version: string; language: string; effectiveFrom: string; mandatoryHash: string; templateKey: string;
  legalReviewed: boolean; current: boolean; bodyMd: string | null; lockedClauses: string[] | null;
};
export type NoticeStatus = { needsReAcknowledgement: boolean; acceptedVersion: string | null; currentVersion: string | null; language: string | null };

const LANGS: Record<string, string> = { en: "English", hi: "Hindi", kn: "Kannada", te: "Telugu", ar: "Arabic" };
export const languageLabel = (code: string): string => LANGS[code] ?? code;

/** Same rule as the server: every locked clause still in the body, verbatim, in order (text may only be added around them). */
export function lockedClausesIntact(body: string, clauses: readonly string[]): boolean {
  let from = 0;
  for (const c of clauses) {
    const at = body.indexOf(c, from);
    if (at < 0) return false;
    from = at + c.length;
  }
  return true;
}

export const currentOf = (list: readonly NoticeVersion[], language: string): NoticeVersion | undefined =>
  list.find((v) => v.language === language && v.current);

export const historyOf = (list: readonly NoticeVersion[], language: string): NoticeVersion[] =>
  list.filter((v) => v.language === language);

export const isStepUpCode = (s: string): boolean => /^\d{6}$/.test(s);

export function reackText(s: NoticeStatus | undefined): string | null {
  if (!s?.needsReAcknowledgement) return null;
  return `The privacy notice changed (accepted v${s.acceptedVersion}, now v${s.currentVersion}). Ask the patient to acknowledge it; care is not blocked.`;
}
