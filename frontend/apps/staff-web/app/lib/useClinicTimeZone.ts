"use client";

import { useEffect, useSyncExternalStore } from "react";
import { apiFetch } from "./apiClient";
import { getClinicTimeZone, loadClinicTimeZone, subscribeClinicTimeZone } from "./clinicTime";
import { decodeAccessToken, SHELL_SKIP_PREFIXES } from "./session";

/** FEF-011: the clinic zone loaded at shell boot (null until then); components re-render when it arrives. */
export function useClinicTimeZone(): string | null {
  return useSyncExternalStore(subscribeClinicTimeZone, getClinicTimeZone, () => null);
}

/** Shell boot (AppShell): load the zone for the signed-in clinic; re-checked on navigation so a login or clinic switch picks it up. */
export function useClinicTimeZoneBoot(pathname: string | null): void {
  useEffect(() => {
    if (SHELL_SKIP_PREFIXES.some((p) => pathname?.startsWith(p))) return;
    const token = localStorage.getItem("nabd_access_token");
    if (!token) return;
    const claims = decodeAccessToken(token);
    void loadClinicTimeZone(apiFetch, claims?.tenantRegion, claims?.tenantId ?? "");
  }, [pathname]);
}
