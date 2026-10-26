"use client";

import StatusPill from "../../ui/StatusPill";
import type { Conflict } from "./useNoteEditor";
import type { SaveState } from "../visit/types";

// FEF-030: the one-line save state shown in the patient strip and in the action bar.
export function saveBadge(a: { conflict: Conflict | null; online: boolean; saveState: SaveState; dirty: boolean; lastSavedAt: Date | null }) {
  if (a.conflict) return <StatusPill tone="warn" icon="alert" label="Sync conflict — review" />;
  if (!a.online || a.saveState === "offline") return <StatusPill tone="sync" icon="sync" label="Offline · local draft" />;
  if (a.saveState === "saving") return <StatusPill tone="sync" icon="sync" label="Saving…" />;
  if (a.saveState === "error") return <StatusPill tone="danger" label="Couldn't save — retrying" />;
  if (a.dirty) return <StatusPill tone="warn" icon="clock" label="Unsaved changes" />;
  if (a.lastSavedAt) return <StatusPill tone="ok" label={`Saved ${a.lastSavedAt.toLocaleTimeString()}`} />;
  return null;
}
