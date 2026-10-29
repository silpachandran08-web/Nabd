// ACC-022: pure logic of the doctor-columns board (GET /v1/queue/board). No React, no fetch.
import type { QueueBoardDoctor, QueueBoardEntry } from "@nabd/api-client/generated";

// The wire shapes come from the generated client; these only mark the fields the server always sends as present.
type Need<T, K extends keyof T> = Omit<T, K> & Required<Pick<T, K>>;
export type BoardEntry = Need<QueueBoardEntry, "id" | "patientId" | "token" | "displayName" | "stage" | "position" | "priority" | "flags">;
export type BoardDoctor = Need<Omit<QueueBoardDoctor, "entries">, "doctorId" | "name" | "state"> & { entries: BoardEntry[] };
export type Board = { v: number; date: string; doctors: BoardDoctor[] };

/** Reassign is allowed only before the consult starts; the server checks again (409 STALE_STATUS). */
export const isMovable = (e: BoardEntry): boolean => e.stage !== "in_consult" && e.stage !== "checkout_pending";

/** "T-14, was T-9" for a reassigned patient, else "T-14". */
export const tokenLabel = (e: Pick<BoardEntry, "token" | "previousToken">): string =>
  e.previousToken ? `T-${e.token}, was T-${e.previousToken}` : `T-${e.token}`;

export const REASONS: { code: string; label: string }[] = [
  { code: "balance_load", label: "Balance the load" },
  { code: "doctor_delayed", label: "Doctor is running late" },
  { code: "patient_request", label: "Patient asked" },
  { code: "doctor_unavailable", label: "Doctor unavailable" },
];

export type KeyAction = { type: "start" } | { type: "target"; index: number } | { type: "cancel" } | { type: "priority" } | null;

/** M starts a move on the focused card, 1-9 picks the target column, Escape cancels, P opens the priority dialog. */
export function keyAction(key: string, moving: boolean): KeyAction {
  const k = key.length === 1 ? key.toLowerCase() : key;
  if (k === "Escape") return moving ? { type: "cancel" } : null;
  if (k === "m") return moving ? { type: "cancel" } : { type: "start" };
  if (k === "p" && !moving) return { type: "priority" };
  if (moving && /^[1-9]$/.test(k)) return { type: "target", index: Number(k) - 1 };
  return null;
}

/** The column a digit names, or null when it is out of range or is the patient's own doctor. */
export function targetDoctor(doctors: BoardDoctor[], fromDoctorId: string, index: number): BoardDoctor | null {
  const d = doctors[index];
  return d && d.doctorId !== fromDoctorId ? d : null;
}

/** Entry id -> doctor id, for drag and drop and the keyboard flow. */
export function ownerOf(doctors: BoardDoctor[], entryId: string): { doctor: BoardDoctor; entry: BoardEntry } | null {
  for (const doctor of doctors) {
    const entry = doctor.entries.find((e) => e.id === entryId);
    if (entry) return { doctor, entry };
  }
  return null;
}
