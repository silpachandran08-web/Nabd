"use client";
// FEF-053/054: route skeleton for a nav item whose screen another squad builds. Same rule as the nav: no grant or no
// entitled module = the no-access state (a direct URL gets nothing); otherwise an honest "not built yet" state.
import type { ReactNode } from "react";
import { useTranslations } from "next-intl";
import { EmptyState, Skeleton } from "../components/ui/StateViews";
import { useEntitlements } from "../lib/entitlements";
import { usePermissions } from "../lib/usePermissions";
import { findItem, itemVisible } from "./navRegistry";
import { navText } from "./navText";
import { useShellNav } from "./useShellNav";

// With children the screen is built: same no-access gate for a direct URL, then the real screen instead of the placeholder.
export default function PlannedScreen({ id, children }: { id: string; children?: ReactNode }) {
  const t = useTranslations("shell");
  const { role } = useShellNav();
  const item = findItem(id);
  const { ready, permissions } = usePermissions();
  const ent = useEntitlements();
  if (!item) return null;
  const screen = navText(t, item, role);
  if (!ready) return <main><Skeleton rows={4} label={t("gate.loading", { screen })} /></main>;
  if (!itemVisible(item, permissions, ent)) return <main><EmptyState icon="shield" title={t("gate.noAccess", { screen })} /></main>;
  if (children) return <>{children}</>;
  return <main><EmptyState icon="info" title={t("gate.notYet", { screen })} hint={t("gate.beingBuilt", { team: item.owner })} /></main>;
}
