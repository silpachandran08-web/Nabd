import type { Metadata } from "next";

// FEF-028: per-route title (the page is a client component, so metadata lives here).
export const metadata: Metadata = { title: "Arrivals · Nabd", description: "Live arrivals board and walk-in registration" };

export default function ArrivalsLayout({ children }: { children: React.ReactNode }) {
  return children;
}
