import type { Metadata } from "next";
import { connection } from "next/server";
import { Poppins } from "next/font/google";
import "./globals.css";
import BffBoot from "./BffBoot";
import TelemetryReporter from "./TelemetryReporter";
import { QueryProvider } from "./lib/query";
import { NextIntlClientProvider } from "next-intl";
import { getMessages } from "next-intl/server";
import { dirOf, isLocale } from "@nabd/i18n";
import { currentTag } from "../i18n/request";
import { scriptFontClasses } from "./fonts";
import I18nBoot from "./I18nBoot";

// DESIGN.md §2.1 — weights 400/500/600/700 only, Poppins is the only Latin family.
const poppins = Poppins({
  variable: "--font-poppins",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
});

export const metadata: Metadata = {
  title: "Nabd",
  description: "Nabd clinic staff sign-in",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  await connection(); // FEF-010: render per request so Next stamps the CSP nonce on its scripts (static pages would carry none).
  const tag = await currentTag(); // FEF-036: lang/dir are rendered on the server, so there is no direction flash
  const lang = isLocale(tag) ? tag : "en";
  const dir = tag === "pseudo-rtl" ? "rtl" : isLocale(tag) ? dirOf(tag) : "ltr";
  const messages = await getMessages();
  return (
    <html lang={lang} dir={dir} className={`${poppins.variable} ${scriptFontClasses(lang)}`}>
      <body>
        <NextIntlClientProvider locale={lang} messages={messages}>
          <I18nBoot locale={lang} />
          <TelemetryReporter />
          <BffBoot />
          {/* FEF-029: each route group's layout adds its own shell: (clinic) nav + top bar + idle lock, the others a plain frame. */}
          <QueryProvider>{children}</QueryProvider>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
