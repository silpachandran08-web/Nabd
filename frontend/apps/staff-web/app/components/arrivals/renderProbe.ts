"use client";

import { useEffect } from "react";

// FEF-028 profiler budget: when a test sets window.__nbRenderProbe = {} before load, every commit of a probed
// component is counted under its id. Inert (one property read) otherwise.
export function useRenderProbe(id: string) {
  useEffect(() => {
    const probe = (window as unknown as { __nbRenderProbe?: Record<string, number> }).__nbRenderProbe;
    if (probe) probe[id] = (probe[id] ?? 0) + 1;
  });
}
