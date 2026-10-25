"use client";

import { useEffect, useState } from "react";
import { SHELL_SKIP_PREFIXES } from "./session";

export * from "./entitlementRules";
import type { Entitlements } from "./entitlementRules";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";
let inflight: Promise<Entitlements | null> | null = null;

function load(): Promise<Entitlements | null> {
  if (inflight) return inflight;
  const token = typeof localStorage === "undefined" ? null : localStorage.getItem("nabd_access_token");
  if (!token) return Promise.resolve(null);
  inflight = fetch(`${API_BASE}/me/entitlements`, { headers: { Authorization: `Bearer ${token}` } })
    .then((r) => (r.ok ? (r.json() as Promise<Entitlements>) : null))
    .catch(() => null)
    .finally(() => { setTimeout(() => { inflight = null; }, 30_000); }); // shared by nav + banner, refreshed on the next page load
  return inflight;
}

export function useEntitlements(pathname: string | null): Entitlements | null {
  const [ent, setEnt] = useState<Entitlements | null>(null);
  const skip = !!pathname && SHELL_SKIP_PREFIXES.some((p) => pathname.startsWith(p));
  useEffect(() => {
    if (skip) return;
    let cancelled = false;
    void load().then((e) => { if (!cancelled && e) setEnt(e); });
    return () => { cancelled = true; };
  }, [skip]);
  return ent;
}
