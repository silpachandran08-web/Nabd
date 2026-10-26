// FEF-030: shapes shared by the consult feature modules (queue, note, rx, closeout, ...). Matches GET /v1/queue, /patients/{id}, /clinical/notes/{queueEntryId}.
import type { NoteAmendmentResponse, NoteResponse, StaffRosterEntry, TransferTargetResponse } from "@nabd/api-client/generated";
import type { Need } from "../../../lib/doctorQueries";
import type { NoteFields } from "../draftStore";

export type TransferTarget = Need<Omit<TransferTargetResponse, "doctors">, "departmentId" | "departmentName"> & { doctors: Need<StaffRosterEntry, "id" | "name">[] };
export type Note = Need<Omit<NoteResponse, "subjective" | "objective" | "assessment" | "plan" | "diagnosis" | "signedAt">, "id" | "queueEntryId" | "patientId" | "doctorId" | "status" | "updatedAt">
  & Record<"subjective" | "objective" | "assessment" | "plan" | "diagnosis" | "signedAt", string | null>;
export type NoteDraft = NoteFields;
export type NoteAmendment = Need<Omit<NoteAmendmentResponse, "diagnosis">, "id" | "amendedBy" | "reason" | "createdAt"> & { diagnosis: string | null };
export type SaveState = "idle" | "saving" | "saved" | "error" | "offline";
