import type { Metadata, Viewport } from "next";
import { connection } from "next/server";
import { headers } from "next/headers";
import { dirOf } from "@nabd/i18n";
import "./globals.css";
import { scriptFontClasses } from "./fonts";
import { currentLocale } from "./lib/messages";

export const metadata: Metadata = { title: "Nabd", robots: { index: false, follow: false }, referrer: "no-referrer" };
export const viewport: Viewport = { width: "device-width", initialScale: 1 };

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  await connection(); // per request, so Next stamps the CSP nonce on its scripts and lang/dir are right in the first byte
  const locale = await currentLocale();
  const theme = (await headers()).get("x-nabd-theme") === "dark" ? "dark" : "light";
  return (
    <html lang={locale} dir={dirOf(locale)} data-theme={theme} className={scriptFontClasses}>
      <body>{children}</body>
    </html>
  );
}
