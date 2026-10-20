import { getPermissions, isOwner } from "./session";

// Restricted UI is absent, never disabled (wireframe rule). Owner holds every grant.
export function can(permission: string): boolean {
  return isOwner() || getPermissions().includes(permission);
}
