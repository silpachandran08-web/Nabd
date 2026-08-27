import type { Metadata } from "next";
import { Poppins } from "next/font/google";
import "./globals.css";
import IdleLockGuard from "./IdleLockGuard";
import LogoutButton from "./LogoutButton";

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

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={poppins.variable}>
      <body>
        {children}
        <LogoutButton />
        <IdleLockGuard />
      </body>
    </html>
  );
}
