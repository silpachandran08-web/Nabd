import type { Metadata } from "next";
import { connection } from "next/server";
import { headers } from "next/headers";
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
import { ThemeBoot } from "./shell/ThemeSwitch";
import { THEME_BOOT_SCRIPT } from "./shell/theme";

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
  // FEF-025: the nonce'd inline script in <head> sets data-theme from the nabd_theme cookie (System: prefers-color-scheme)
  // while the HTML is parsed, so the first frame is already in the user's theme. data-theme is never a React prop, so a
  // router.refresh cannot reset it; suppressHydrationWarning because the script sets it before React hydrates.
  const nonce = (await headers()).get("x-nonce") ?? undefined;
  return (
    <html lang={lang} dir={dir} suppressHydrationWarning className={`${poppins.variable} ${scriptFontClasses(lang)}`}>
      <head>
        <script nonce={nonce} suppressHydrationWarning dangerouslySetInnerHTML={{ __html: THEME_BOOT_SCRIPT }} />
      </head>
      <body>
        <NextIntlClientProvider locale={lang} messages={messages}>
          <I18nBoot locale={lang} />
          <ThemeBoot />
          <TelemetryReporter />
          <BffBoot />
          {/* FEF-029: each route group's layout adds its own shell: (clinic) nav + top bar + idle lock, the others a plain frame. */}
          <QueryProvider>{children}</QueryProvider>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
