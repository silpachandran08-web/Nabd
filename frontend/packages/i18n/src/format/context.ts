// The locale, digit style and SAR-symbol choice of the signed-in user, set once by the shell (I18nBoot); call sites pass nothing.
import { DEFAULT_LOCALE, type Locale } from "../locales.ts";

export type DigitStyle = "latn" | "arab";
export type FormatContext = { locale: Locale; digitStyle: DigitStyle; sarSymbol: boolean };
let ctx: FormatContext = { locale: DEFAULT_LOCALE, digitStyle: "latn", sarSymbol: false };

export const getFormatContext = (): FormatContext => ctx;
export function setFormatContext(next: Partial<FormatContext>): void { ctx = { ...ctx, ...next }; }

/** Intl tag for a UI locale. Digits are Latin unless the arab style is on AND the language is Arabic. */
export function intlTag(locale: Locale, currency?: string, digitStyle: DigitStyle = ctx.digitStyle): string {
  const base = locale === "en" ? (currency === "INR" ? "en-IN" : "en-US") : locale === "ar" ? "ar" : `${locale}-IN`;
  return `${base}-u-nu-${locale === "ar" && digitStyle === "arab" ? "arab" : "latn"}`;
}
