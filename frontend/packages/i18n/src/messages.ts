// FEF-036: catalogue loader. One JSON file per namespace and language (messages/<locale>/<ns>.json), loaded per request/route,
// so a route ships only the namespaces it uses (<= 15 KB gz). A missing key or file falls back to English.
import { CATALOGUED, type Locale } from "./locales.ts";
import { pseudoCatalogue } from "./pseudo.ts";

export const NAMESPACES = ["shell", "queue"] as const;
export type Namespace = (typeof NAMESPACES)[number];
export type Messages = Record<string, Record<string, unknown>>;
export type PseudoMode = "pseudo" | "pseudo-rtl";

const load = async (locale: string, ns: Namespace): Promise<Record<string, unknown>> =>
  (await import(`../messages/${locale}/${ns}.json`, { with: { type: "json" } })).default;

const merge = (base: Record<string, unknown>, over: Record<string, unknown>): Record<string, unknown> => ({ ...base, ...over });

export async function loadMessages(locale: Locale | PseudoMode, namespaces: readonly Namespace[] = NAMESPACES): Promise<Messages> {
  const out: Messages = {};
  for (const ns of namespaces) {
    const en = await load("en", ns);
    if (locale === "pseudo" || locale === "pseudo-rtl") out[ns] = pseudoCatalogue(en, locale === "pseudo-rtl");
    else out[ns] = CATALOGUED.includes(locale) && locale !== "en" ? merge(en, await load(locale, ns).catch(() => ({}))) : en;
  }
  return out;
}
