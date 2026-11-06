// FEF-035: which consult sections hold unsaved local text, kept outside React so the one save indicator and the
// beforeunload guard (page level) see every section, including forms nested in panels and dialogs.
import { useSyncExternalStore } from "react";
import type { SaveState } from "./visit/types";

const dirtySections = new Set<string>();
const subs = new Set<() => void>();

export function setSectionDirty(key: string, dirty: boolean): void {
  if (dirty === dirtySections.has(key)) return;
  if (dirty) dirtySections.add(key); else dirtySections.delete(key);
  for (const s of subs) s();
}
export const anySectionDirty = () => dirtySections.size > 0;
const subscribe = (cb: () => void) => { subs.add(cb); return () => { subs.delete(cb); }; };
/** True while any local-only section (follow-up, orders, allergy forms) holds unsent text. */
export function useAnySectionDirty(): boolean {
  return useSyncExternalStore(subscribe, anySectionDirty, () => false);
}

const RANK: Record<SaveState, number> = { idle: 0, saved: 1, offline: 2, saving: 3, error: 4 };
/** One indicator for several server-synced sections: the state that needs the most attention wins, the newest save shows. */
export function combineSave(parts: { saveState: SaveState; dirty: boolean; lastSavedAt: Date | null; conflict: boolean }[]) {
  return {
    saveState: parts.reduce<SaveState>((w, p) => (RANK[p.saveState] > RANK[w] ? p.saveState : w), "idle"),
    dirty: parts.some((p) => p.dirty),
    conflict: parts.some((p) => p.conflict),
    lastSavedAt: parts.reduce<Date | null>((m, p) => (p.lastSavedAt && (!m || p.lastSavedAt > m) ? p.lastSavedAt : m), null),
  };
}
