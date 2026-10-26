// FEF-005: screen-lock state that survives reload and is shared between tabs.
import { clearDrafts } from "../components/consult/draftStore.ts";
import { clearDrugIndex } from "./drugIndex.ts";

export const LOCK_KEY = "nabd_locked_at";
export const LOCK_CHANNEL = "nabd-lock";
export const IDLE_POLICY_KEY = "session.idle_lock_minutes";
export const DEFAULT_IDLE_MINUTES = 5;
export const WARN_MS = 30_000;

/** Clinic setting -> minutes: whole number 2-30, anything else falls back to the 5 min default. */
export function clampIdleMinutes(v: unknown): number {
  const n = typeof v === "string" && /^\d+$/.test(v.trim()) ? Number(v) : typeof v === "number" ? v : NaN;
  return Number.isInteger(n) && n >= 2 && n <= 30 ? n : DEFAULT_IDLE_MINUTES;
}

/** Idle phase for `idleMs` of inactivity: nothing, the 30 s countdown (with seconds left), or lock. */
export function idlePhase(idleMs: number, timeoutMs: number): { phase: "active" | "warn" | "lock"; secondsLeft: number } {
  if (idleMs >= timeoutMs) return { phase: "lock", secondsLeft: 0 };
  if (idleMs >= timeoutMs - WARN_MS) return { phase: "warn", secondsLeft: Math.ceil((timeoutMs - idleMs) / 1000) };
  return { phase: "active", secondsLeft: 0 };
}

// Both stores on purpose: sessionStorage is per tab, localStorage is what a brand-new tab sees.
export function readLockedAt(): number | null {
  try {
    const v = sessionStorage.getItem(LOCK_KEY) ?? localStorage.getItem(LOCK_KEY);
    return v ? Number(v) || Date.now() : null;
  } catch {
    return null;
  }
}

function chan(): BroadcastChannel | null {
  return typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel(LOCK_CHANNEL);
}

function set(locked: boolean): void {
  try {
    for (const s of [sessionStorage, localStorage]) { if (locked) s.setItem(LOCK_KEY, String(Date.now())); else s.removeItem(LOCK_KEY); }
  } catch { /* blocked storage: the in-memory lock still holds for this tab */ }
  const c = chan();
  c?.postMessage({ type: locked ? "lock" : "unlock" });
  c?.close();
}
export const persistLock = () => set(true);
export const persistUnlock = () => set(false);

/** Calls back on lock/unlock from other tabs; returns the unsubscribe. */
export function onLockMessage(cb: (locked: boolean) => void): () => void {
  const c = chan();
  if (!c) return () => {};
  c.onmessage = (e) => { if (e.data?.type === "lock" || e.data?.type === "unlock") cb(e.data.type === "lock"); };
  return () => c.close();
}

/** Every sign-out path: forget the lock and delete the drafts database. */
export async function clearLocalSessionData(): Promise<void> {
  try { sessionStorage.removeItem(LOCK_KEY); localStorage.removeItem(LOCK_KEY); } catch { /* ignore */ }
  await clearDrafts();
  await clearDrugIndex(); // CLN-020: the per-doctor drug index is deleted with the session (T-029)
}
