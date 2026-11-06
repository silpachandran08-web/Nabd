"use client";

// FEF-030 (DOC-029): sign the note and hand the patient to checkout, moved out of consult/page.tsx.
import { useTranslations } from "next-intl";
import { useState } from "react";
import { moveBody } from "../../../lib/queueMove";
import { deleteVisitDrafts } from "../draftStore";
import type { AuthedFetch, Problem, Row } from "../shared";
import type { useNoteEditor } from "../note/useNoteEditor";
import type { Note } from "../visit/types";

type Editor = ReturnType<typeof useNoteEditor>;

export function useCloseOut(authedFetch: AuthedFetch, entry: Row, n: Editor, handoffPending: boolean, setShellError: (e: string | null) => void, setNotice: (s: string | null) => void, setEntryStatus: (status: string) => void) {
  const t = useTranslations("consult.useCloseOut");
  const [completing, setCompleting] = useState(false);

  async function completeConsultation() {
    setCompleting(true);
    setShellError(null);
    try {
      if (n.noteStatus !== "signed") {
        // Always flush a save first — even an untouched note needs one draft row to exist before it
        // can be signed, and this also catches the last few seconds of typing the 10s interval hasn't.
        n.ctl.flushCheck();
        const saveRes = await n.saveNote();
        if (!saveRes?.ok) {
          setShellError(n.ctl.conflictNow() ? t("noteChanged") : (navigator.onLine ? t("saveFailed") : t("offline")));
          return;
        }
        const signRes = await authedFetch(`/clinical/notes/${entry.id}/sign`, { method: "POST" });
        // CLN-006: 409 means an earlier attempt already signed it; carry on to the checkout handoff.
        if (!signRes?.ok && signRes?.status !== 409) {
          const pr: Problem = await signRes?.json().catch(() => ({ title: "Error", detail: t("signFailed") }));
          setShellError(pr?.detail || t("signFailed"));
          return;
        }
        n.setNoteStatus("signed");
        void deleteVisitDrafts(entry.id); // FEF-035: nothing of a signed visit stays on the device
        const signed: Partial<Note> | null = await signRes.json().catch(() => null);
        n.setSignedAt(typeof signed?.signedAt === "string" ? signed.signedAt : new Date().toISOString());
      }
      const statusRes = await authedFetch(`/queue/${entry.id}/status`, { method: "PATCH", body: moveBody("checkout_pending", entry.status) });
      if (!statusRes?.ok) {
        const pr: Problem | null = await statusRes?.json().catch(() => null);
        setShellError(pr?.detail ? t("handoffFailedDetail", { detail: pr.detail }) : t("handoffFailed"));
        return;
      }
      setEntryStatus("checkout_pending");
      setNotice(t("sentToCheckout"));
      // Stay in the workspace (don't auto-close): follow-up and amendments remain reachable.
    } catch {
      setShellError(n.noteStatus === "signed" || handoffPending
        ? t("unreachableHandoff")
        : t("unreachableSign"));
    } finally {
      setCompleting(false);
    }
  }
  return { completing, completeConsultation };
}
