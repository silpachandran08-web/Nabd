"use client";

// FEF-032 fix (S19-BUG-1/2): the old single setup page kept its drafts (new charge, holiday, shift, licence, specialty,
// payroll month, profile error), the loaded lists and the failed-save Retry banner in page state, so they survived tab
// switches and were dropped on leaving Setup. Each tab is now its own route, so that state lives here instead: one store
// per mount of the setup layout's tab frame (sibling tab routes share it; the wizard, Departments and Booking rules
// render outside the frame, so going there drops it, as before). Plain .ts (no JSX) so a unit test can render it.
import { createContext, createElement, useCallback, useContext, useState, useSyncExternalStore, type ReactNode, type SetStateAction } from "react";

export type SetupStore = { has: (key: string) => boolean; get: (key: string) => unknown; set: (key: string, v: unknown) => void; subscribe: (fn: () => void) => () => void };

export function createSetupStore(): SetupStore {
  const values = new Map<string, unknown>();
  const subs = new Set<() => void>();
  return {
    has: (key) => values.has(key),
    get: (key) => values.get(key),
    set: (key, v) => { values.set(key, v); subs.forEach((fn) => fn()); },
    subscribe: (fn) => { subs.add(fn); return () => { subs.delete(fn); }; },
  };
}

const SetupStoreContext = createContext<SetupStore | null>(null);

export function SetupStoreProvider({ children, store }: { children?: ReactNode; store?: SetupStore }) {
  const [own] = useState(createSetupStore);
  return createElement(SetupStoreContext.Provider, { value: store ?? own }, children);
}

/** useState whose value lives in the setup store under `key`: it outlives the tab route that set it. */
export function useSetupState<T>(key: string, initial: T): [T, (v: SetStateAction<T>) => void] {
  const store = useContext(SetupStoreContext);
  if (!store) throw new Error("useSetupState must be used inside the setup layout (SetupStoreProvider)");
  const [first] = useState<T>(() => initial);
  const read = useCallback(() => (store.has(key) ? (store.get(key) as T) : first), [store, key, first]);
  const value = useSyncExternalStore(store.subscribe, read, read);
  const set = useCallback((v: SetStateAction<T>) => store.set(key, typeof v === "function" ? (v as (prev: T) => T)(read()) : v), [store, key, read]);
  return [value, set];
}
