"use client";
// FEF-036/037: hands the server-resolved locale and the user's digit style (cookie nabd_digits, mirrored by the BFF from
// /me/preferences) to the format module. Browser only: the server renders with the module defaults because a module global
// would leak between concurrent requests. shortcut: an ar + arab-digit page therefore re-renders its numbers after hydration.
import { setFormatContext, type Locale } from "@nabd/i18n";

export default function I18nBoot({ locale }: { locale: Locale }) {
  if (typeof document !== "undefined") {
    setFormatContext({ locale, digitStyle: /(?:^|; )nabd_digits=arab/.test(document.cookie) ? "arab" : "latn" });
  }
  return null;
}
