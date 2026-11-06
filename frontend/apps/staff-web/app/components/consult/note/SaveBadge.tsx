"use client";

import type { useTranslations } from "next-intl";
import StatusPill from "../../ui/StatusPill";
import type { SaveState } from "../visit/types";

// FEF-030: the one-line save state shown in the patient strip and in the action bar.
export function saveBadge(t: ReturnType<typeof useTranslations>, a: { conflict: boolean; online: boolean; saveState: SaveState; dirty: boolean; lastSavedAt: Date | null }) {
  if (a.conflict) return <StatusPill tone="warn" icon="alert" label={t("syncConflictReview")} />;
  if (!a.online || a.saveState === "offline") return <StatusPill tone="sync" icon="sync" label={t("offlineLocalDraft")} />;
  if (a.saveState === "saving") return <StatusPill tone="sync" icon="sync" label={t("saving")} />;
  if (a.saveState === "error") return <StatusPill tone="danger" label={t("couldnTSaveRetrying")} />;
  if (a.dirty) return <StatusPill tone="warn" icon="clock" label={t("unsavedChanges")} />;
  if (a.lastSavedAt) return <StatusPill tone="ok" label={t("savedAt", { time: a.lastSavedAt.toLocaleTimeString() })} />;
  return null;
}
