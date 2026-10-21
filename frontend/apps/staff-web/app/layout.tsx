import type { Metadata } from "next";
import { connection } from "next/server";
import { Poppins } from "next/font/google";
import "./globals.css";
import AppShell from "./AppShell";
import IdleLockGuard from "./IdleLockGuard";
import { QueryProvider } from "./lib/query";

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
  return (
    <html lang="en" className={poppins.variable}>
      <body>
        <IdleLockGuard>
          <QueryProvider><AppShell>{children}</AppShell></QueryProvider>
        </IdleLockGuard>
      </body>
    </html>
  );
}
