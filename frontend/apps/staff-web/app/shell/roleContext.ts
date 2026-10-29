"use client";
// FEF-052: role-context override (owner who is also a doctor). Per tab/session, never an authorization input.
import { useSyncExternalStore } from "react";
const KEY = "nabd_role_ctx";
const listeners = new Set<() => void>();
const read = (): string | null => { try { return sessionStorage.getItem(KEY); } catch { return null; } };
export function setRoleContext(role: string) {
  try { sessionStorage.setItem(KEY, role); } catch { /* blocked storage: the switch still works until reload */ }
  listeners.forEach((l) => l());
}
export const useRoleOverride = (): string | null =>
  useSyncExternalStore((l) => { listeners.add(l); return () => { listeners.delete(l); }; }, read, () => null);
