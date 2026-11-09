"use client";

// FEF-035: local-only draft of a form that is submitted in one go (follow-up booking, order and allergy forms: the server has
// no draft endpoint for them). Written 800 ms after the last edit, restored when the form mounts again in the same tab session,
// deleted when the form is submitted or cancelled. Reports to draftStatus so the indicator and the beforeunload guard see it.
import { useEffect, useRef, useState } from "react";
import { deleteDraft, readDraft, writeDraft, type DraftSection } from "./draftStore";
import { setSectionDirty } from "./draftStatus";
import { LOCAL_DRAFT_MS } from "./note/noteLogic";

export function useSectionDraft<T>(entryId: string, section: DraftSection, value: T, setValue: (v: T) => void, blank: T) {
  const [restored, setRestored] = useState(false);
  const loaded = useRef(false);
  const key = `${entryId}#${section}`;
  const blankJson = JSON.stringify(blank);

  useEffect(() => {
    let cancelled = false;
    void readDraft<T>(entryId, section).then((d) => {
      if (cancelled) return;
      loaded.current = true;
      if (d && JSON.stringify(d.data) !== blankJson) { setValue(d.data); setRestored(true); }
    });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per form mount
  }, [entryId, section]);

  const json = JSON.stringify(value);
  useEffect(() => {
    if (!loaded.current) return;
    const dirty = json !== blankJson;
    setSectionDirty(key, dirty);
    if (!dirty) { void deleteDraft(entryId, section); return; }
    const t = setTimeout(() => { void writeDraft({ entryId, section, data: JSON.parse(json), baseUpdatedAt: null, savedAt: new Date().toISOString() }); }, LOCAL_DRAFT_MS);
    return () => clearTimeout(t);
  }, [json, blankJson, key, entryId, section]);

  useEffect(() => () => setSectionDirty(key, false), [key]);

  /** Submitted or cancelled: nothing left to restore. */
  const clear = () => { setSectionDirty(key, false); setRestored(false); void deleteDraft(entryId, section); };
  return { restored, clear };
}
