// FEF-028: walk-in validation and request bodies, pure. Order of checks and messages are unchanged from the page.
import type { WalkInState } from "./walkInReducer";

/** First problem with the form, or null. `hasPatient`: an existing or already-registered patient is chosen. */
export function walkInProblem(s: WalkInState, hasPatient: boolean, minor: boolean): string | null {
  if (!s.doctorId) return "Pick a doctor.";
  if (hasPatient) return null;
  if (!s.name || !s.phone || !s.dob) return "Fill in the new patient's name, phone and date of birth.";
  if (minor && !s.guardian) return "A guardian is required for patients under 18. Search for the guardian's record below.";
  if (minor && (!s.guardianConsent || !s.guardianChannel)) return "Confirm the guardian's consent and how it was given.";
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
