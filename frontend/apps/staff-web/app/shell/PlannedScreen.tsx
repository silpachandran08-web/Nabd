"use client";
// FEF-053/054: route skeleton for a nav item whose screen another squad builds. Same rule as the nav: no grant or no
// entitled module = the no-access state (a direct URL gets nothing); otherwise an honest "not built yet" state.
import { usePathname } from "next/navigation";
import { EmptyState, Skeleton } from "../components/ui/StateViews";
import { useEntitlements } from "../lib/entitlements";
import { usePermissions } from "../lib/usePermissions";
import { findItem, itemVisible } from "./navRegistry";

export default function PlannedScreen({ id }: { id: string }) {
  const item = findItem(id);
  const { ready, permissions } = usePermissions();
  const ent = useEntitlements(usePathname());
  if (!item) return null;
  if (!ready) return <main><Skeleton rows={4} label={`Loading ${item.label}`} /></main>;
  if (!itemVisible(item, permissions, ent)) return <main><EmptyState icon="shield" title={`You do not have access to ${item.label}.`} /></main>;
  return <main><EmptyState icon="info" title={`${item.label} is not available yet.`} hint={`This screen is being built by ${item.owner}.`} /></main>;
}
