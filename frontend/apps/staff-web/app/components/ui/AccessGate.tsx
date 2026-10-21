"use client";

import { useSyncExternalStore, type ReactNode } from "react";
import { can } from "../../lib/permissions";
import type { AppPermission } from "@nabd/api-client/types";
import { EmptyState, Skeleton } from "./StateViews";

const subscribe = () => () => {};
const serverSnapshot = (): boolean | null => null;

// Page-level permission gate for routes that are reachable by URL but hidden from the nav. The
// children (and their data fetches) are mounted ONLY for a role that holds the grant; a role
// without it gets the shared no-access state, never a flash of the restricted UI. The grant lives
// in the browser's token, so the server render and first hydration pass show a skeleton.
export default function AccessGate({ permission, surface, children }: { permission: AppPermission; surface: string; children: ReactNode }) {
  const allowed = useSyncExternalStore(subscribe, () => can(permission), serverSnapshot);
  if (allowed === null) return <main><Skeleton rows={4} label={`Loading ${surface}`} /></main>;
  if (!allowed) return <main><EmptyState icon="shield" title={`Your role doesn't have access to ${surface}.`} /></main>;
  return <>{children}</>;
}
