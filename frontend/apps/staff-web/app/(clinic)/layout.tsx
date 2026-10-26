import AppShell from "../AppShell";
import IdleLockGuard from "../IdleLockGuard";
import { LiveProvider } from "../lib/live";

// FEF-029: the clinic shell (nav, top bar, idle lock, live stream) exists only for these routes; structure replaces the old path-prefix list.
export default function ClinicLayout({ children }: { children: React.ReactNode }) {
  return <IdleLockGuard><LiveProvider><AppShell>{children}</AppShell></LiveProvider></IdleLockGuard>;
}
