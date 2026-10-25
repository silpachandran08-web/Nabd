import type { AppPermission } from "@nabd/api-client/types";
import { getPermissions } from "./session";

// Restricted UI is absent, never disabled (wireframe rule). FEF-019: no owner bypass: the token's
// permissions claim is the only input (the Owner preset has no clinical:edit/approve, RolePresets).
// FEF-014: AppPermission is generated from the spec, so a grant no endpoint knows (typo, renamed) fails tsc.
export function can(permission: AppPermission): boolean {
  return getPermissions().includes(permission);
}
