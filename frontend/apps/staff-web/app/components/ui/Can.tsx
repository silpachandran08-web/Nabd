"use client";

import type { ReactNode } from "react";
import type { AppPermission } from "@nabd/api-client/types";
import { usePermissions } from "../../lib/usePermissions";

/** Renders children only for a role holding `permission`; otherwise nothing (absent, never disabled). `any` = one of several. */
export default function Can({ permission, any, children }: { permission?: AppPermission; any?: AppPermission[]; children: ReactNode }) {
  const { ready, can } = usePermissions();
  const list = any ?? (permission ? [permission] : []);
  return ready && list.some(can) ? <>{children}</> : null;
}
