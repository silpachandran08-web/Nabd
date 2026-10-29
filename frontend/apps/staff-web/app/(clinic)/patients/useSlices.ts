import { useState } from "react";

/** FEF-031: several related fields in ONE useState; `slice(k)` hands back the familiar [value, setValue] pair (value or updater). */
export function useSlices<T extends object>(init: T) {
  const [s, set] = useState(init);
  return <K extends keyof T>(k: K): [T[K], (v: T[K] | ((p: T[K]) => T[K])) => void] => [
    s[k],
    (v) => set((p) => ({ ...p, [k]: typeof v === "function" ? (v as (x: T[K]) => T[K])(p[k]) : v })),
  ];
}
