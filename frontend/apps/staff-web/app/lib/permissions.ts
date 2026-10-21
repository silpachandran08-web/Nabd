import type { AppPermission } from "@nabd/api-client/types";
import { getPermissions, isOwner } from "./session";

// Restricted UI is absent, never disabled (wireframe rule). Owner holds every grant.
// FEF-014: AppPermission is generated from the spec, so a grant no endpoint knows (typo, renamed) fails tsc.
export function can(permission: AppPermission): boolean {
  return isOwner() || getPermissions().includes(permission);
}
