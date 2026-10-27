"use client";

import { useSyncExternalStore } from "react";

/** CLN-008: a CSS media query as state (false on the server and before the first client render). */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (cb) => { const m = window.matchMedia(query); m.addEventListener("change", cb); return () => m.removeEventListener("change", cb); },
    () => window.matchMedia(query).matches,
    () => false,
  );
}
