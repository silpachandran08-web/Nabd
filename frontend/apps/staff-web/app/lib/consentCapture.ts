// IDC-004/007: pure helpers for the desk consent block (no React; unit-tested in tests/unit/s15-idc004-consent.test.mjs).
export type Toggles = { processing: boolean; messaging: boolean; marketing: boolean };
export type CaptureBody = {
  decisions: { purpose: "processing" | "messaging" | "marketing"; granted: boolean }[];
  noticeVersion: string; language: string; channel: "DESK" | "ASSISTED";
  guardianPatientId?: string; witnessStaffIds?: string[]; emergencyTreatmentBasis?: boolean;
};
export type ConsentState = {
  purposes: Record<string, { granted: boolean; noticeVersion?: string | null; language?: string | null; channel?: string | null }>;
  needsReack: boolean; processable: boolean; needsConsent: boolean; emergencyTreatmentBasis: boolean; notices: Record<string, string>;
};

export const NO_CONSENT: Toggles = { processing: false, messaging: false, marketing: false };

/** Every purpose is sent, so a declined processing consent is recorded as a refusal; the server stores optional refusals only when they withdraw a grant. */
export function buildCaptureBody(t: Toggles, notices: Record<string, string>, language: string, o: {
  channel?: "DESK" | "ASSISTED"; guardianPatientId?: string | null; witnessStaffIds?: string[]; emergency?: boolean; minor?: boolean;
} = {}): CaptureBody | null {
  const noticeVersion = notices[language];
  if (!noticeVersion) return null;
  return {
    decisions: [
      { purpose: "processing", granted: t.processing },
      { purpose: "messaging", granted: t.processing && t.messaging },
      // a child never receives marketing: the toggle is not offered and is never sent as granted
      { purpose: "marketing", granted: !o.minor && t.processing && t.marketing },
    ],
    noticeVersion, language, channel: o.channel ?? "DESK",
    ...(o.guardianPatientId ? { guardianPatientId: o.guardianPatientId } : {}),
    ...(o.witnessStaffIds?.length ? { witnessStaffIds: o.witnessStaffIds } : {}),
    ...(!t.processing && o.emergency ? { emergencyTreatmentBasis: true } : {}),
  };
}

const PROBLEMS: Record<string, string> = {
  "notice-version-stale": "The privacy notice changed. Reload the page and ask again.",
  "guardian-required": "A patient under 18 needs the linked guardian's consent.",
  "minor-marketing-blocked": "A patient under 18 never receives marketing.",
  "second-witness-required": "This clinic needs a second staff member as witness.",
  "witness-unknown": "A witness is not a staff member of this clinic.",
};
export const consentProblemText = (slug: string, fallback: string): string => PROBLEMS[slug] ?? fallback;

/** Explainer content per language; null text = not recorded / reviewed yet. */
export type Explainer = { language: string; text: string | null; audioUrl: string | null; audioSeconds?: number | null };
export const EXPLAINER_LANGUAGES = ["en", "hi", "kn", "te"] as const;
export const explainerUsable = (e: Explainer | null | undefined): boolean => !!e && !!e.text && (!e.audioUrl || (e.audioSeconds ?? 0) <= 60);

/**
 * IDC-007.1: explainer content per language. Audio files are static assets served from the CDN (no PHI) and are referenced here once
 * recorded and reviewed by the DPO; until then text/audio stay null and the desk reads the notice aloud.
 * TODO(needs-external-action): record hi/kn/te audio (at most 60 s), write the hi/kn/te large-text summaries, DPO review of all four.
 */
export const EXPLAINERS: Record<string, Explainer> = {
  en: { language: "en", text: "We keep your health record to look after you. You can also say yes or no to messages about your care on WhatsApp, and to offers. You can change your mind at any time at the desk.", audioUrl: null, audioSeconds: null },
  hi: { language: "hi", text: null, audioUrl: null, audioSeconds: null },
  kn: { language: "kn", text: null, audioUrl: null, audioSeconds: null },
  te: { language: "te", text: null, audioUrl: null, audioSeconds: null },
};
