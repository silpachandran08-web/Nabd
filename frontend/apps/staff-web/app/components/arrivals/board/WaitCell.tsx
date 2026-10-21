"use client";

import { useSyncExternalStore } from "react";
import { waitMinutes } from "./boardLogic";

// The once-a-minute clock lives here, not on the page: a tick re-renders only these small cells.
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;
let minute = 0;
function subscribe(fn: () => void) {
  if (listeners.size === 0) minute = Date.now(); // a remount after a quiet spell starts from now, not the last tick
  listeners.add(fn);
  if (!timer) timer = setInterval(() => { minute = Date.now(); listeners.forEach((l) => l()); }, 60_000);
  return () => {
    listeners.delete(fn);
    if (listeners.size === 0) { clearInterval(timer); timer = undefined; }
  };
}
// The snapshot is the last tick (or first read) so it is stable between ticks, as useSyncExternalStore requires.
const snapshot = () => (minute ||= Date.now());
const serverSnapshot = () => 0;
export const useMinuteClock = () => useSyncExternalStore(subscribe, snapshot, serverSnapshot);

export default function WaitCell({ createdAt, unit = "m" }: { createdAt: string; unit?: "m" | " min" }) {
  const now = useMinuteClock();
  return <>{waitMinutes(createdAt, now)}{unit}</>;
}
