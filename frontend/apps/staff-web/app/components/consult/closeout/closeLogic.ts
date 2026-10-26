// FEF-030 (DOC-029): what to check before the consultation is closed. Pure.
import type { NoteFields } from "../draftStore";
import type { PrescriptionItem } from "../shared";

export type CloseView = { issues: string[]; hasDrugs: boolean; handoffPending: boolean; closedOut: boolean };

export function closeView(a: { note: NoteFields; noteStatus: "draft" | "signed"; rxItems: PrescriptionItem[]; rxStatus: "draft" | "signed"; entryStatus: string | undefined }): CloseView {
  const hasDrugs = a.rxItems.some((i) => i.drugName.trim() !== "");
  const noteEmpty = Object.values(a.note).every((v) => v.trim() === "");
  const issues: string[] = [];
  if (a.noteStatus !== "signed") {
    if (a.note.diagnosis.trim() === "") issues.push("No diagnosis is entered.");
    if (noteEmpty) issues.push("The note is empty.");
  }
  if (hasDrugs && a.rxStatus !== "signed") issues.push("The prescription is not signed, so the patient would leave without it.");
  return {
    issues, hasDrugs,
    handoffPending: a.noteStatus === "signed" && a.entryStatus !== "checkout_pending",
    closedOut: a.noteStatus === "signed" && a.entryStatus === "checkout_pending",
  };
}
