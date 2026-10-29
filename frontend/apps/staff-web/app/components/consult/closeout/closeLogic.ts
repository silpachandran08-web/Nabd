// FEF-030 (DOC-029): what to check before the consultation is closed. Pure.
import type { NoteFields } from "../draftStore";
import type { PrescriptionItem } from "../shared";

/** What is open before closing; the panel shows the catalogue text for each id (consult.closeOutPanel.issue.*). */
export type CloseIssue = "noDiagnosis" | "noteEmpty" | "rxUnsigned";
export type CloseView = { issues: CloseIssue[]; hasDrugs: boolean; handoffPending: boolean; closedOut: boolean };

export function closeView(a: { note: NoteFields; noteStatus: "draft" | "signed"; rxItems: PrescriptionItem[]; rxStatus: "draft" | "signed"; entryStatus: string | undefined }): CloseView {
  const hasDrugs = a.rxItems.some((i) => i.drugName.trim() !== "");
  const noteEmpty = Object.values(a.note).every((v) => v.trim() === "");
  const issues: CloseIssue[] = [];
  if (a.noteStatus !== "signed") {
    if (a.note.diagnosis.trim() === "") issues.push("noDiagnosis");
    if (noteEmpty) issues.push("noteEmpty");
  }
  if (hasDrugs && a.rxStatus !== "signed") issues.push("rxUnsigned");
  return {
    issues, hasDrugs,
    handoffPending: a.noteStatus === "signed" && a.entryStatus !== "checkout_pending",
    closedOut: a.noteStatus === "signed" && a.entryStatus === "checkout_pending",
  };
}
