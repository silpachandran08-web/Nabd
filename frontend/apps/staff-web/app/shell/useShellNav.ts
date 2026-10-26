"use client";
import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import { usePermissions } from "../lib/usePermissions";
import { useEntitlements } from "../lib/entitlements";
import { decodeAccessToken, SHELL_SKIP_PREFIXES } from "../lib/session";
import { navFor } from "./navRegistry";
import { useRoleOverride } from "./roleContext";

/** The resolved nav for the signed-in staff member: role context, switchable contexts, permitted items. */
export function useShellNav() {
  const pathname = usePathname();
  const skip = SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p));
  const { permissions } = usePermissions();
  const ent = useEntitlements(pathname);
  const override = useRoleOverride();
  const [claims, setClaims] = useState<{ owner: boolean; specialty: string | null; tenantId: string | null }>({ owner: false, specialty: null, tenantId: null });
  useEffect(() => {
    let c = { owner: false, specialty: null as string | null, tenantId: null as string | null };
    try {
      const t = skip ? null : localStorage.getItem("nabd_access_token");
      const d = t ? decodeAccessToken(t) : null;
      c = { owner: d?.owner === true, specialty: d?.specialty ?? null, tenantId: d?.tenantId ?? null };
    } catch { /* no storage: treated as signed out */ }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setClaims(c);
  }, [pathname, skip, permissions]);
  const input = { perms: permissions, owner: claims.owner, ent, override, specialty: claims.specialty };
  return { skip, input, owner: claims.owner, tenantId: claims.tenantId, ...navFor(input) };
}
