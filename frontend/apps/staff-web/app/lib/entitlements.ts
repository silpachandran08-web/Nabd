"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "./api";
import { getTenantId } from "./session";

export * from "./entitlementRules";
import { pharmacyEdition, standaloneEdition, type Entitlements, type StandaloneEdition } from "./entitlementRules";

// value = the clinic (tenant) id last answered as that edition, like nabd_solo_mode
const EDITION_KEY: Record<StandaloneEdition, string> = { pharmacy: "nabd_pharmacy_edition", lab: "nabd_lab_edition" };
const remembered = (edition: StandaloneEdition): boolean => {
  try { const t = getTenantId(); return !!t && localStorage.getItem(EDITION_KEY[edition]) === t; } catch { return false; }
};

/** T1-BUG-15: the edition from the answer when there is one; while it is unknown (pending or failed), the last answer for this clinic
 * on this device, so a pharmacy owner keeps the counter menu. Reads storage: call it in an effect or after `settled`, never in a first render. */
export function pharmacyKnown(ent: Entitlements | null): boolean {
  if (ent) return pharmacyEdition(ent);
  return remembered("pharmacy");
}

/** PHL-056: the same for either standalone edition: the answer when there is one, else what this device last saw for this clinic. */
export function editionKnown(ent: Entitlements | null): StandaloneEdition | null {
  if (ent) return standaloneEdition(ent);
  return remembered("pharmacy") ? "pharmacy" : remembered("lab") ? "lab" : null;
}

function remember(t: string | null, e: Entitlements): void {
  try {
    for (const [edition, key] of Object.entries(EDITION_KEY)) {
      if (t && e.edition === edition) localStorage.setItem(key, t);
      else if (localStorage.getItem(key) === t) localStorage.removeItem(key);
    }
  } catch { /* blocked storage: nothing remembered, same as before */ }
}

let inflight: Promise<Entitlements | null> | null = null;

function load(): Promise<Entitlements | null> {
  if (inflight) return inflight;
  const token = typeof localStorage === "undefined" ? null : localStorage.getItem("nabd_access_token");
  if (!token) return Promise.resolve(null);
  const tenant = getTenantId(); // read now: the answer belongs to the clinic that asked
  inflight = api.get<Entitlements>("/me/entitlements")
    .then((e): Entitlements | null => {
      if (!(Array.isArray(e?.modules) && Array.isArray(e?.killedFeatures))) return null; // a malformed body is treated as unknown
      remember(tenant, e);
      return e;
    })
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
