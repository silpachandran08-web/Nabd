"use client";

import type { ReactNode } from "react";
import type { AppPermission } from "@nabd/api-client/types";
import { usePermissions } from "../../lib/usePermissions";
import { EmptyState, Skeleton } from "./StateViews";

// Page-level permission gate. The children (and their data fetches) are mounted ONLY for a role that
// holds the grant, so a role without it sends no request that would 403 and sees the shared
// no-access state. The grant lives in the browser's token, so the server render shows a skeleton.
export default function AccessGate({ permission, surface, title, hint, children }: { permission: AppPermission; surface: string; title?: string; hint?: string; children: ReactNode }) {
  const { ready, can } = usePermissions();
  if (!ready) return <main><Skeleton rows={4} label={`Loading ${surface}`} /></main>;
  if (!can(permission)) return <main><EmptyState icon="shield" title={title ?? `You do not have access to ${surface}.`} hint={hint} /></main>;
  return <>{children}</>;
}
