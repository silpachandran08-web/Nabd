"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import { SHELL_SKIP_PREFIXES } from "./session";
import type { PharmacyCapabilities } from "./pharmacyRules";

export const CAPABILITY_REFRESH_MS = 5_000; // a mode change reaches every open session within 5 s

/** Polls while the tab is visible; `enabled` is false for staff without pharmacy:view (no call at all). */
export function usePharmacyCapabilities(pathname: string | null, enabled: boolean): PharmacyCapabilities | null {
  const [caps, setCaps] = useState<PharmacyCapabilities | null>(null);
  const skip = !enabled || (!!pathname && SHELL_SKIP_PREFIXES.some((p) => pathname.startsWith(p)));
  useEffect(() => {
    if (skip) return;
    let cancelled = false;
    const load = () => {
      if (typeof document !== "undefined" && document.hidden) return;
      api.get<PharmacyCapabilities>("/pharmacy/capabilities")
        .then((c) => { if (!cancelled && c && typeof c.mode === "string" && Array.isArray(c.features)) setCaps(c); })
        .catch(() => { /* unknown: nothing is hidden */ });
    };
    load();
    const t = setInterval(load, CAPABILITY_REFRESH_MS);
    return () => { cancelled = true; clearInterval(t); };
  }, [skip]);
  return caps;
}
