"use client";

// FEF-030: the note editor's state and its seven autosave rules, moved out of consult/page.tsx unchanged
// (characterised by tests/e2e/s15-fef-030-autosave-characterisation.mjs, written before the move):
//  1 never save while a conflict is unresolved          2 never save before the server copy is known
//  3 offline: keep a local draft, send nothing         4 a note signed elsewhere is never overwritten
//  5 the server changed under a dirty note: pause       6 a failed save keeps the draft and retries on the next tick
//  7 success deletes the local draft only if nothing was typed while the save was in flight
import { useCallback, useEffect, useRef, useState } from "react";
import { deleteDraft, writeDraft, type NoteFields } from "../draftStore";
import type { AuthedFetch } from "../shared";
import { useOnline } from "./useOnline";
import { BLANK_DRAFT, LOCAL_DRAFT_MS, sameFields, toFields } from "./noteLogic";
import { createAutosave } from "../../../lib/autosave";
import type { Note, NoteDraft, SaveState } from "../visit/types";

export type Conflict = { mine: NoteFields; server: NoteFields; serverUpdatedAt: string | null };

export function useNoteEditor(authedFetch: AuthedFetch, entryId: string, setNotice: (n: string | null) => void) {
  const [note, setNote] = useState<NoteDraft>(BLANK_DRAFT);
  const [noteStatus, setNoteStatus] = useState<"draft" | "signed">("draft");
  const [signedAt, setSignedAt] = useState<string | null>(null);
  // Editing and saving wait until the server note is known; a failed load must never let a save
  // replace the server's draft with blank or partial fields (T1-BUG-3).
  const [noteLoad, setNoteLoadState] = useState<"loading" | "ok" | "error">("loading");
  const [orphanText, setOrphanText] = useState<NoteFields | null>(null); // local text not applied because the note was signed elsewhere
  const [dirty, setDirty] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);
  const [conflict, setConflict] = useState<Conflict | null>(null);
  const [conflictOpen, setConflictOpen] = useState(false);
  const online = useOnline();

  const noteLoadRef = useRef<"loading" | "ok" | "error">("loading");
  const dirtyRef = useRef(dirty);
  const noteRef = useRef(note);
  const conflictRef = useRef(conflict);
  const baseUpdatedRef = useRef<string | null>(null); // server note's updatedAt when we last synced
  const needsServerCheckRef = useRef(false); // compare with the server copy before the next PATCH
  const localTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    dirtyRef.current = dirty;
    noteRef.current = note;
    conflictRef.current = conflict;
  }, [dirty, note, conflict]);

  const setNoteLoad = useCallback((v: "loading" | "ok" | "error") => { noteLoadRef.current = v; setNoteLoadState(v); }, []);

  const autosaveRef = useRef<ReturnType<typeof createAutosave> | null>(null);
  function scheduleLocalDraft() {
    autosaveRef.current?.touch(); // OPS-006: idle 3 s / max 30 s debounce (app/lib/autosave.ts)
    if (localTimerRef.current) clearTimeout(localTimerRef.current);
    localTimerRef.current = setTimeout(() => {
      if (!dirtyRef.current) return;
      void writeDraft({ entryId, data: noteRef.current, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
    }, LOCAL_DRAFT_MS);
  }

  async function saveNote() {
    if (conflictRef.current) return null; // rule 1: never overwrite while a conflict is unresolved
    if (noteLoadRef.current !== "ok") return null; // rule 2: the server copy was never read: nothing to save over
    const sent = noteRef.current;
    if (!navigator.onLine) { // rule 3
      setSaveState("offline");
      await writeDraft({ entryId, data: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      needsServerCheckRef.current = true;
      return null;
    }
    setSaveState("saving");
    try {
      if (needsServerCheckRef.current) {
        const g = await authedFetch(`/clinical/notes/${entryId}`);
        if (g?.status === 200) {
          const server: Note = await g.json();
          if (server.status === "signed") {
            // Rule 4: signed from another session while this device held unsaved text: never overwrite a
            // signed note. Lock the view to the server copy and keep the local text visible.
            needsServerCheckRef.current = false;
            baseUpdatedRef.current = server.updatedAt;
            setNote(toFields(server));
            setNoteStatus("signed");
            setSignedAt(server.signedAt);
            setDirty(false);
            setSaveState("idle");
            setOrphanText(sameFields(toFields(server), sent) ? null : sent);
            void deleteDraft(entryId);
            return null;
          }
          if (server.updatedAt !== baseUpdatedRef.current && !sameFields(toFields(server), sent)) { // rule 5
            setConflict({ mine: sent, server: toFields(server), serverUpdatedAt: server.updatedAt });
            setConflictOpen(true);
            setSaveState("error");
            return null;
          }
          baseUpdatedRef.current = server.updatedAt;
        } else if (g && g.status !== 404) {
          setSaveState("error");
          return g;
        }
        needsServerCheckRef.current = false;
      }
      const res = await authedFetch(`/clinical/notes/${entryId}`, { method: "PATCH", body: JSON.stringify(sent) });
      if (res?.ok) {
        if (noteRef.current === sent) setDirty(false);
        setLastSavedAt(new Date());
        setSaveState("saved");
        try {
          const saved: Partial<Note> = await res.clone().json();
          if (typeof saved.updatedAt === "string") baseUpdatedRef.current = saved.updatedAt;
        } catch { /* body not needed */ }
        if (noteRef.current === sent) void deleteDraft(entryId); // rule 7
      } else { // rule 6
        setSaveState("error");
        needsServerCheckRef.current = true;
        await writeDraft({ entryId, data: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      }
      return res;
    } catch {
      setSaveState(navigator.onLine ? "error" : "offline");
      needsServerCheckRef.current = true;
      await writeDraft({ entryId, data: sent, baseUpdatedAt: baseUpdatedRef.current, savedAt: new Date().toISOString() });
      return null;
    }
  }

  // Latest saveNote for the timers below (the closure it captured is one render old by the time they fire).
  const saveRef = useRef(saveNote);
  useEffect(() => { saveRef.current = saveNote; });

  // OPS-006 (DP-27): one autosave when typing pauses 3 s, at the latest 30 s after the first unsaved edit. A failed save keeps
  // `dirty` set, so the flush touches again and retries after the next idle pause. No audit row until sign/amend.
  useEffect(() => {
    const saver = createAutosave(() => {
      if (!dirtyRef.current) return;
      void saveRef.current().finally(() => { if (dirtyRef.current) saver.touch(); });
    });
    autosaveRef.current = saver;
    return () => { saver.cancel(); autosaveRef.current = null; };
  }, []);

  // On reconnect, retry straight away (the server copy is compared first).
  useEffect(() => {
    if (online && dirtyRef.current) void saveRef.current();
  }, [online]);

  // Coming back online means the server copy may have moved: compare before the next PATCH.
  useEffect(() => {
    const on = () => { needsServerCheckRef.current = true; };
    window.addEventListener("online", on);
    return () => window.removeEventListener("online", on);
  }, []);

  function updateField(field: keyof NoteDraft, value: string) {
    if (noteLoadRef.current !== "ok") return;
    setNote((prev) => ({ ...prev, [field]: value }));
    setDirty(true);
    scheduleLocalDraft();
  }

  function resolveConflict(merged: NoteFields) {
    const rebase = conflict?.serverUpdatedAt ?? null;
    setConflict(null);
    setConflictOpen(false);
    conflictRef.current = null;
    setNote(merged);
    setDirty(true);
    // Re-base on the server copy the doctor just compared against.
    needsServerCheckRef.current = true;
    baseUpdatedRef.current = rebase;
    scheduleLocalDraft();
    setNotice("Choices applied. The note will be saved after one more check against the server copy.");
  }

  return {
    note, noteStatus, signedAt, noteLoad, orphanText, dirty, saveState, lastSavedAt, conflict, conflictOpen, online,
    saveNote, updateField, resolveConflict, setOrphanText, setConflictOpen, setNoteStatus, setSignedAt,
    /** Reads and flags on the editor's refs, for the visit loader, the close-out and the workspace (a ref itself must not be handed around). */
    ctl: {
      dirtyNow: () => dirtyRef.current,
      conflictNow: () => conflictRef.current !== null,
      loadState: () => noteLoadRef.current,
      setBase: (v: string | null) => { baseUpdatedRef.current = v; },
      flagServerCheck: () => { needsServerCheckRef.current = true; },
      /** Before closing: compare with the server copy first if anything is unsaved. */
      flushCheck: () => { needsServerCheckRef.current = needsServerCheckRef.current || dirtyRef.current; },
    },
    /** What the visit loader needs to put the server copy (or a restored local draft) into the editor. */
    load: { setNote, setNoteLoad, setDirty, setConflict, setNoteStatus, setSignedAt, setOrphanText, setConflictOpen },
  };
}
