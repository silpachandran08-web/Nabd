"use client";

// FEF-030 (CLN-006): amendments to a signed note, moved out of consult/page.tsx.
import { useState } from "react";
import type { AuthedFetch, Problem } from "../shared";
import type { NoteAmendment } from "../visit/types";

export function useAmendments(authedFetch: AuthedFetch, entryId: string, onSaved: () => void) {
  const [amendments, setAmendments] = useState<NoteAmendment[]>([]);
  const [amendReason, setAmendReason] = useState("");
  const [amendDiagnosis, setAmendDiagnosis] = useState("");
  const [amending, setAmending] = useState(false);
  const [amendError, setAmendError] = useState<string | null>(null);

  async function submitAmendment() {
    if (amendReason.trim().length < 5) return;
    setAmending(true);
    setAmendError(null);
    try {
      const res = await authedFetch(`/clinical/notes/${entryId}/amendments`, {
        method: "POST",
        body: JSON.stringify({ reason: amendReason, diagnosis: amendDiagnosis.trim() || undefined }),
      });
      if (res?.ok) {
        setAmendReason("");
        setAmendDiagnosis("");
        onSaved();
        const aRes = await authedFetch(`/clinical/notes/${entryId}/amendments`);
        if (aRes?.ok) setAmendments(await aRes.json());
      } else {
        const pr: Problem | null = await res?.json().catch(() => null);
        setAmendError(pr?.detail || "Couldn't save the amendment. Try again.");
      }
    } catch {
      setAmendError("Couldn't reach the server. The amendment was not saved; try again.");
    } finally {
      setAmending(false);
    }
  }
  return { amendments, amendReason, amendDiagnosis, amending, amendError, setAmendments, setAmendReason, setAmendDiagnosis, submitAmendment };
}
export type Amendments = ReturnType<typeof useAmendments>;
