import { api } from "../../lib/api";

// CLN-023: the signed Rx PDF (stored once on the server, same bytes every time), sending it to the patient as an OTP secure link,
// and revocation by the prescriber. GET /clinical/prescriptions/{id}/pdf?size=a4|a5 (clinical:view),
// POST .../pdf/send (clinical:view + patients:edit), POST .../revoke { reason } (clinical:edit or clinical:approve; the server also
// requires the visit's licensed prescriber or an approver).

export type PdfSize = "a4" | "a5";
export { REVOKE_REASON_MAX, revokeReasonOk } from "./rxRevokeRules";

const path = (queueEntryId: string) => `/clinical/prescriptions/${encodeURIComponent(queueEntryId)}`;

/** Opens the stored PDF in a new tab for printing or saving; false when it could not be fetched. */
export async function openRxPdf(queueEntryId: string, size: PdfSize): Promise<boolean> {
  try {
    const blob = await api.get<Blob>(`${path(queueEntryId)}/pdf?size=${size}`, { as: "blob" });
    const url = URL.createObjectURL(blob);
    window.open(url, "_blank", "noopener");
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    return true;
  } catch {
    return false;
  }
}

export function sendRxPdf(queueEntryId: string): Promise<{ linkId: string; expiresAt: string }> {
  return api.post<{ linkId: string; expiresAt: string }>(`${path(queueEntryId)}/pdf/send`, {});
}

export function revokeRx(queueEntryId: string, reason: string): Promise<{ status: string; revokedAt: string }> {
  return api.post<{ status: string; revokedAt: string }>(`${path(queueEntryId)}/revoke`, { reason: reason.trim() });
}
