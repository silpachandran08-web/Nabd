"use client";
// FEF-058: the chain owner's facility scope. "all" = the read-only aggregate view of every facility; otherwise the one facility of the session
// token. Per tab/session and never an authorization input (the token decides what the server returns). The switcher also leaves the facility
// names it loaded here, so the aggregate screen can list them without a second call.
import { useSyncExternalStore } from "react";
const KEY = "nabd_facility_scope";
export type Facility = { id: string; name: string; brand: string };
let known: Facility[] = [];
const listeners = new Set<() => void>();
const read = (): string | null => { try { return sessionStorage.getItem(KEY); } catch { return null; } };
const emit = () => listeners.forEach((l) => l());
export function setFacilityScope(scope: "all" | "one") {
  try { if (scope === "all") sessionStorage.setItem(KEY, "all"); else sessionStorage.removeItem(KEY); } catch { /* blocked storage: lasts until reload */ }
  emit();
}
export function rememberFacilities(list: Facility[]) { known = list; emit(); }
const sub = (l: () => void) => { listeners.add(l); return () => { listeners.delete(l); }; };
export const useFacilityScope = (): "all" | "one" => useSyncExternalStore(sub, () => (read() === "all" ? "all" : "one"), () => "one");
export const useKnownFacilities = (): Facility[] => useSyncExternalStore(sub, () => known, () => known);
