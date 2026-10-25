// FEF-036: next-intl request config. No locale in the URL: proxy.ts resolves it (cookie -> clinic default -> Accept-Language)
// and passes it in the x-nabd-locale request header, so <html lang dir> is right in the first byte.
import { getRequestConfig } from "next-intl/server";
import { headers } from "next/headers";
import { DEFAULT_LOCALE, LOCALE_HEADER, isLocale, loadMessages, type Locale, type PseudoMode } from "@nabd/i18n";

/** The tag the proxy resolved: a real locale, or a pseudo mode (only when NABD_PSEUDO=1, for the CI pseudo-locale project). */
export async function currentTag(): Promise<Locale | PseudoMode> {
  const v = (await headers()).get(LOCALE_HEADER);
  if (isLocale(v)) return v;
  if (process.env.NABD_PSEUDO === "1" && (v === "pseudo" || v === "pseudo-rtl")) return v;
  return DEFAULT_LOCALE;
}

export default getRequestConfig(async () => {
  const tag = await currentTag();
  return { locale: tag === "pseudo" || tag === "pseudo-rtl" ? "en" : tag, messages: await loadMessages(tag) };
});
