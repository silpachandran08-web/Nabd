"use client";

// FEF-030: opening a visit (the old openConsult): the patient, the note (+ a lost local draft), the prescription and the signing authority,
// read one after another exactly as before. The workspace is mounted per visit, so a visit switch unmounts it: the cleanup
// cancels this visit's reads, which is what "opening another visit cancels the previous visit's reads" used to do by hand.
import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { can } from "../../../lib/permissions";
import { FRESH_MS, loadPatients, visitIsWarm, visitRead, visitReadKey } from "../../../lib/doctorQueries";
import { deleteDraft, readDraft, takeLostDrafts, type NoteFields } from "../draftStore";
import { BLANK_DRAFT, sameFields, toFields } from "../note/noteLogic";
import type { useNoteEditor } from "../note/useNoteEditor";
import { BLANK_RX_ITEM, padItems, rxFingerprint } from "../rx/rxLogic";
import type { RxDraftData, RxPad } from "../rx/useRxPad";
import type { SignAuthority } from "../signAuthority";
import type { Amendments } from "../amend/useAmendments";
import type { AuthedFetch, PatientDetail, Prescription, Row } from "../shared";
import type { Note } from "./types";

interface LoadArgs {
  row: Row; authedFetch: AuthedFetch; n: ReturnType<typeof useNoteEditor>; pad: RxPad; amend: Amendments;
  setPatient: (p: PatientDetail | null) => void; setUnavailable: (v: boolean) => void; setNotice: (s: string | null) => void; setShellError: (s: string | null) => void;
}

export function useVisitLoad(a: LoadArgs) {
  const qc = useQueryClient();
  const { row } = a;
  useEffect(() => {
    const ctl = new AbortController();
    const signal = ctl.signal;
    const stale = () => signal.aborted;
    const { n, pad, amend, authedFetch } = a;
    void (async () => {
      try {
        // A prefetched visit (hover / focus on the queue) is served from the cache; otherwise this reads it.
        const warm = visitIsWarm(qc, row.id);
        if (can("patients:view")) {
          const got = await loadPatients(qc, [row.patientId], signal);
          if (stale()) return;
          const fresh = got.get(row.patientId);
          if (fresh) a.setPatient(fresh);
        }

        const nr = await visitRead<Note>(qc, "note", row.id, FRESH_MS);
        if (stale()) return;
        if (nr.status === 401) return;
        let server: Note | null = null;
        if (nr.status === 200) {
          server = nr.body!;
          n.ctl.setBase(server.updatedAt);
          n.load.setNote(toFields(server));
          n.load.setNoteStatus(server.status as "draft" | "signed");
          n.load.setSignedAt(server.signedAt);
          // A cached copy may be up to FRESH_MS old: the first autosave compares with the server before it writes (unchanged conflict check).
          if (warm) n.ctl.flagServerCheck();
          if (server.status === "signed") {
            const aRes = await authedFetch(`/clinical/notes/${row.id}/amendments`, { signal });
            if (stale()) return;
            if (aRes?.ok) amend.setAmendments(await aRes.json());
          }
        } else if (nr.status === 404 && typeof nr.problem?.type === "string" && nr.problem.type.endsWith("/patient-not-found")) {
          // No patient details, no further reads, no retry: the answer will not change on its own.
          a.setPatient(null);
          a.setUnavailable(true);
          return;
        } else if (nr.status !== 404) {
          n.load.setNoteLoad("error");
        }
        if (n.ctl.loadState() === "loading") n.load.setNoteLoad("ok");

        // A local draft means the last save never reached the server (offline, crash, reload).
        // Not when the note failed to load: the draft is kept untouched for the next successful open.
        const draft = n.ctl.loadState() === "ok" ? await readDraft<NoteFields>(row.id) : null;
        if (stale()) return;
        if (takeLostDrafts() > 0) a.setNotice("An unsent local draft could not be recovered after the page reloaded. Re-check this note.");
        if (draft) {
          const serverFields = server ? toFields(server) : BLANK_DRAFT;
          if (server?.status === "signed") {
            // Same as the reconnect path: never applied to a signed note, but listed for an amendment.
            void deleteDraft(row.id);
            if (!sameFields(draft.data, serverFields)) n.load.setOrphanText(draft.data);
          } else if (sameFields(draft.data, serverFields)) {
            void deleteDraft(row.id);
          } else if ((server?.updatedAt ?? null) === draft.baseUpdatedAt) {
            n.load.setNote(draft.data);
            n.load.setDirty(true);
            n.ctl.flagServerCheck();
            a.setNotice(`Restored an unsaved local draft from ${new Date(draft.savedAt).toLocaleString()}.`);
          } else {
            n.load.setConflict({ mine: draft.data, server: serverFields, serverUpdatedAt: server?.updatedAt ?? null });
            n.load.setConflictOpen(true);
            a.setNotice("The note changed on the server since your local draft. Choose what to keep.");
          }
        }

        const rRes = await visitRead<Prescription>(qc, "rx", row.id, FRESH_MS);
        if (stale()) return;
        const serverRx = rRes.status === 200 ? padItems(rRes.body!) : [{ ...BLANK_RX_ITEM }];
        const rxSigned = rRes.status === 200 && rRes.body!.status === "signed";
        pad.load.setServer(serverRx);
        if (rRes.status === 200) pad.load.setRxStatus(rRes.body!.status as "draft" | "signed");
        // FEF-035: an Rx draft left by a crash/reload or an offline spell, same rules as the note above.
        const rxDraft = rRes.status === 200 || rRes.status === 404 ? await readDraft<RxDraftData>(row.id, "rx") : null;
        if (stale()) return;
        if (takeLostDrafts() > 0) a.setNotice("An unsent local draft could not be recovered after the page reloaded. Re-check this prescription.");
        if (rxDraft) {
          const serverFp = rxFingerprint(serverRx);
          if (rxSigned || rxFingerprint(rxDraft.data.items) === serverFp) {
            void deleteDraft(row.id, "rx");
          } else if (rxDraft.data.base === serverFp) {
            pad.load.restore(rxDraft.data.items);
            a.setNotice(`Restored an unsaved local draft from ${new Date(rxDraft.savedAt).toLocaleString()}.`);
          } else {
            pad.load.setConflict({ mine: rxDraft.data.items, server: serverRx });
            a.setNotice("The prescription changed on the server since your local draft. Choose what to keep.");
          }
        }
        const sRes = await visitRead<SignAuthority>(qc, "sign-authority", row.id, FRESH_MS);
        if (stale()) return;
        pad.load.setSignAuthority(sRes.status === 200 ? sRes.body! : "error");
      } catch {
        if (!stale()) {
          if (n.ctl.loadState() === "loading") n.load.setNoteLoad("error");
          a.setShellError("Couldn't reach the server. Check your connection, then reopen the visit.");
        }
      }
    })();
    return () => {
      ctl.abort();
      for (const w of ["note", "rx", "sign-authority"] as const) void qc.cancelQueries({ queryKey: visitReadKey(w, row.id), exact: true });
    };
    // The workspace is keyed by the visit: `a` holds stable setters and refs, so this runs once per visit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [row.id, qc]);
}
