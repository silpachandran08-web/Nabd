// CLN-005: GET /clinical/prescriptions/{id}/signing-authority -> { canSign, reason }. reason is the 403 code sign would
// return. When the caller cannot sign, the Sign control is absent (house rule) and this line says why.
export type SignAuthority = { canSign: boolean; reason: string | null };

export function signBlockMessage(a: SignAuthority | null | "error"): string | null {
  if (a === null) return "Checking signing rights…";
  if (a === "error") return "Couldn't check signing rights. Reopen the visit to try again.";
  if (a.canSign) return null;
  switch (a.reason) {
    case "not-licensed-clinician": return "Needs a registered clinician to sign. Ask the clinic owner to add your registration number.";
    case "licence-expired": return "Your registration has expired. Renew it, or ask the clinic owner for a time-limited override, to sign.";
    case "break-glass-cannot-sign": return "Emergency access is read-only and cannot sign.";
    default: return "Send to doctor to sign.";
  }
}
