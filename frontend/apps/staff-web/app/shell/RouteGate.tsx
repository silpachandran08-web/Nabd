"use client";
// FEF-057: a direct URL into a counter shell route the session may not see (no grant, or module not entitled) gets the no-access state
// instead of the page, so the screen never mounts or fetches. Same gate as the nav (navRegistry blockedRoute -> itemVisible).
import { usePathname } from "next/navigation";
import { EmptyState } from "../components/ui/StateViews";
import { blockedRoute } from "./navRegistry";
import { useShellNav } from "./useShellNav";

export default function RouteGate({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { role, input } = useShellNav();
  const blocked = blockedRoute(role, input, pathname); // pathname only: ?tab= would need useSearchParams and a Suspense bailout for every page
  if (blocked) return <main><EmptyState icon="shield" title={`You do not have access to ${blocked.label}.`} /></main>;
  return <>{children}</>;
}
