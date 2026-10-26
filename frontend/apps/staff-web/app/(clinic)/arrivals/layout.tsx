import type { Metadata } from "next";
import AccessGate from "../../components/ui/AccessGate";

// FEF-028: per-route title (the page is a client component, so metadata lives here).
export const metadata: Metadata = { title: "Arrivals · Nabd", description: "Live arrivals board and walk-in registration" };

export default function ArrivalsLayout({ children }: { children: React.ReactNode }) {
  return <AccessGate permission="queue:view" surface="arrivals" title="No access to the arrivals board" hint="Your role doesn't include the live queue. Ask your clinic owner if you need it.">{children}</AccessGate>;
}
