// CLN-005: GET /clinical/prescriptions/{id}/signing-authority -> { canSign, reason }. reason is the 403 code sign would
// return. When the caller cannot sign, the Sign control is absent (house rule) and this line says why.
export type SignAuthority = { canSign: boolean; reason: string | null };

/** Catalogue id (consult.rxPanel.signBlock.*) of the line that says why Sign is absent, or null when the caller may sign. */
export function signBlockCode(a: SignAuthority | null | "error"): "checking" | "error" | "notLicensed" | "licenceExpired" | "breakGlass" | "sendToDoctor" | null {
  if (a === null) return "checking";
  if (a === "error") return "error";
  if (a.canSign) return null;
  switch (a.reason) {
    case "not-licensed-clinician": return "notLicensed";
    case "licence-expired": return "licenceExpired";
    case "break-glass-cannot-sign": return "breakGlass";
    default: return "sendToDoctor";
  }
}
