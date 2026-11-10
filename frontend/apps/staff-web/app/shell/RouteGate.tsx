"use client";
// FEF-057: a direct URL into a counter shell route (navRegistry counterRouteState, same itemVisible gate as the nav). The page mounts
// only once its grant and plan module are confirmed, so a module-off screen never fetches (S18-BUG-6). A neutral skeleton shows only
// before the first entitlements answer; a failed answer is fail-closed with a manual Retry (no loop, no blank app).
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { useEntitlementsStatus } from "../lib/entitlements";
import { counterRouteState } from "./navRegistry";
import { useShellNav } from "./useShellNav";
import { navText } from "./navText";

export default function RouteGate({ children }: { children: React.ReactNode }) {
  const t = useTranslations("shell");
  const pathname = usePathname();
  const { role, input } = useShellNav();
  const { ent, settled, retry } = useEntitlementsStatus(); // one state object: ent and settled change in the same render
  const gate = counterRouteState(role, { ...input, ent }, pathname, settled); // pathname only: ?tab= would need useSearchParams and a Suspense bailout
  if (gate.kind === "open") return <>{children}</>;
  const screen = navText(t, gate.item, role);
  if (gate.kind === "wait") return <main><Skeleton rows={4} label={t("gate.loading", { screen })} /></main>;
  if (gate.kind === "unverified") return <main><ErrorState message={t("gate.unverified", { screen })} onRetry={retry} /></main>;
  return <main><EmptyState icon="shield" title={t("gate.noAccess", { screen })} /></main>;
}
