// FEF-036: the one place that knows which UI locales exist and how a locale is chosen. No URL segment: the cookie wins.
export const LOCALES = ["en", "hi", "kn", "te", "ta", "ml", "bn", "ar"] as const;
export type Locale = (typeof LOCALES)[number];
export const DEFAULT_LOCALE: Locale = "en";
/** Languages with a shipped catalogue. Every other supported locale renders the English catalogue (and its own lang/dir/fonts). */
export const CATALOGUED: readonly Locale[] = ["en", "hi", "ar"];
/** Label of each language in its own script (language picker). */
export const LOCALE_LABELS: Record<Locale, string> = {
  en: "English", hi: "हिन्दी", kn: "ಕನ್ನಡ", te: "తెలుగు", ta: "தமிழ்", ml: "മലയാളം", bn: "বাংলা", ar: "العربية",
};
export const LOCALE_COOKIE = "nabd_locale";
export const CLINIC_LOCALE_COOKIE = "nabd_clinic_locale";
export const LOCALE_HEADER = "x-nabd-locale";

export const isLocale = (v: unknown): v is Locale => typeof v === "string" && (LOCALES as readonly string[]).includes(v);
export const dirOf = (l: Locale): "ltr" | "rtl" => (l === "ar" ? "rtl" : "ltr");

/** Best supported locale for an Accept-Language header (q-ordered; "hi-IN" matches "hi"); null when none match. */
export function matchAcceptLanguage(header: string | null | undefined): Locale | null {
  const ranked = (header ?? "").split(",").map((part, i) => {
    const [tag, ...params] = part.trim().split(";");
    const q = Number(params.map((p) => p.trim()).find((p) => p.startsWith("q="))?.slice(2) ?? 1);
    return { base: tag.trim().toLowerCase().split("-")[0], q: Number.isNaN(q) ? 0 : q, i };
  }).filter((e) => e.base && e.q > 0).sort((a, b) => b.q - a.q || a.i - b.i);
  return ranked.map((e) => e.base).find(isLocale) ?? null;
}

/** User preference, else clinic default, else the browser's best match, else English. Unsupported values are skipped, not errors. */
export function resolveLocale(input: { user?: string | null; clinic?: string | null; acceptLanguage?: string | null }): Locale {
  if (isLocale(input.user)) return input.user;
  if (isLocale(input.clinic)) return input.clinic;
  return matchAcceptLanguage(input.acceptLanguage) ?? DEFAULT_LOCALE;
}
