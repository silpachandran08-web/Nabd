// FEF-028: walk-in validation and request bodies, pure. Order of checks and messages are unchanged from the page.
import type { WalkInState } from "./walkInReducer";

/** First problem with the form as a catalogue key of arrivals.walkIn (the dialog translates it), or null. `hasPatient`: an existing or already-registered patient is chosen. */
export type WalkInProblemKey = "pickDoctor" | "needNew" | "needGuardian" | "needConsent";
export function walkInProblem(s: WalkInState, hasPatient: boolean, minor: boolean): WalkInProblemKey | null {
  if (!s.doctorId) return "pickDoctor";
  if (hasPatient) return null;
  if (!s.name || !s.phone || !s.dob) return "needNew";
  if (minor && !s.guardian) return "needGuardian";
  if (minor && (!s.guardianConsent || !s.guardianChannel)) return "needConsent";
  return null;
}

/** POST /patients body. confirmedNotDuplicate is only sent after the receptionist reviewed the match list. */
export const registerBody = (s: WalkInState, minor: boolean, confirmedNotDuplicate: boolean) => ({
  name: s.name, phone: s.phone, dob: s.dob, gender: s.gender,
  guardianId: minor ? s.guardian?.id ?? null : null,
  ...(minor ? { guardianConsent: { confirmed: true, channel: s.guardianChannel } } : {}),
  ...(confirmedNotDuplicate ? { confirmedNotDuplicate: true } : {}),
});

/** POST /queue/check-in body. */
export const checkInBody = (s: WalkInState, patientId: string) => ({
  patientId, doctorId: s.doctorId, presentingComplaint: s.complaint.trim() || null, paymentMode: s.paymentMode,
});
