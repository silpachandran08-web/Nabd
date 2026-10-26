"use client";

import { useCallback, useEffect, useMemo, useSyncExternalStore } from "react";
import type { AppPermission } from "@nabd/api-client/types";
import { decodeAccessToken } from "./session";

const KEY = "nabd_access_token";
const RECHECK_MS = 30_000; // a refreshed token (<= 15 min) is written by apiClient in this tab: no storage event, so re-read

const listeners = new Set<() => void>();
const emit = () => listeners.forEach((l) => l());
function subscribe(l: () => void) {
  listeners.add(l);
  window.addEventListener("storage", emit);
  document.addEventListener("visibilitychange", emit);
  const timer = window.setInterval(emit, RECHECK_MS);
  return () => { listeners.delete(l); window.removeEventListener("storage", emit); document.removeEventListener("visibilitychange", emit); window.clearInterval(timer); };
}
// The snapshot is the token string itself (a stable primitive), so nothing is parsed per render.
const read = (): string | null => { try { return localStorage.getItem(KEY) ?? ""; } catch { return ""; } };
const server = (): string | null => null;

export type PermissionState = { ready: boolean; permissions: readonly string[]; can: (p: AppPermission) => boolean };

/** The signed-in staff member's grants, re-read when the token changes (role change takes effect at the next refresh, no re-login). */
export function usePermissions(): PermissionState {
  const token = useSyncExternalStore(subscribe, read, server);
  const permissions = useMemo(() => (token ? decodeAccessToken(token)?.permissions ?? [] : []), [token]);
  const can = useCallback((p: AppPermission) => permissions.includes(p), [permissions]);
  useEffect(() => { emit(); }, []); // pick up a token written between SSR and hydration
  return { ready: token !== null, permissions, can };
}
