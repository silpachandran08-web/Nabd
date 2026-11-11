import { api } from "../../../lib/api";

/** CLN-014: opens the signed consent PDF (GET /nursing/procedure-consents/{id}/pdf, nursing:view) in a new tab for viewing or printing. */
export async function openConsentPdf(consentId: string): Promise<boolean> {
  try {
    const blob = await api.get<Blob>(`/nursing/procedure-consents/${encodeURIComponent(consentId)}/pdf`, { as: "blob" });
    const url = URL.createObjectURL(blob);
    window.open(url, "_blank", "noopener");
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    return true;
  } catch {
    return false;
  }
}
