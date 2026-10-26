"use client";

import { useEffect, useState } from "react";
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
  const [ent, setEnt] = useState<Entitlements | null>(null);
  useEffect(() => {
    let cancelled = false;
    void load().then((e) => { if (!cancelled && e) setEnt(e); });
    return () => { cancelled = true; };
  }, []);
  return ent;
}
