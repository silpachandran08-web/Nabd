"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "./api";

export * from "./entitlementRules";
import type { Entitlements } from "./entitlementRules";

let inflight: Promise<Entitlements | null> | null = null;

function load(): Promise<Entitlements | null> {
  if (inflight) return inflight;
  const token = typeof localStorage === "undefined" ? null : localStorage.getItem("nabd_access_token");
  if (!token) return Promise.resolve(null);
  inflight = api.get<Entitlements>("/me/entitlements")
    .then((e): Entitlements | null => (Array.isArray(e?.modules) && Array.isArray(e?.killedFeatures) ? e : null)) // a malformed body is treated as unknown
    .catch(() => null)
    .finally(() => { setTimeout(() => { inflight = null; }, 30_000); }); // shared by nav + banner, refreshed on the next page load
  return inflight;
}

export function useEntitlements(): Entitlements | null {
  return useEntitlementsStatus().ent;
}

/** FEF-057: entitlements plus whether the first answer (success or failure) has arrived. `settled` never goes back to false, so a
 * retry or later refetch never swaps a page out again (S17-BUG-3); `retry` is manual only, so a failing call cannot loop. */
export function useEntitlementsStatus(): { ent: Entitlements | null; settled: boolean; retry: () => void } {
  const [s, setS] = useState<{ ent: Entitlements | null; settled: boolean }>({ ent: null, settled: false });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let cancelled = false;
    void load().then((e) => { if (!cancelled) setS((p) => ({ ent: e ?? p.ent, settled: true })); });
    return () => { cancelled = true; };
  }, [attempt]);
  const retry = useCallback(() => { inflight = null; setAttempt((a) => a + 1); }, []);
  return { ...s, retry };
}
