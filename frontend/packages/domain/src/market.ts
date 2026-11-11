// FEF-040: the tax names a clinic's market uses (region is fixed at provisioning). Labels only: rates and amounts come from the
// server's tax engine (TaxService: GST for India, VAT elsewhere). India splits GST into CGST + SGST (intra-state) or IGST.
export type TaxRegime = { label: "GST" | "VAT"; split: boolean; registration: "GSTIN" | "VAT no." };
const GST: TaxRegime = { label: "GST", split: true, registration: "GSTIN" };
const VAT: TaxRegime = { label: "VAT", split: false, registration: "VAT no." };
const BY_REGION: Record<string, TaxRegime> = { IN: GST, SA: VAT, AE: VAT, QA: VAT };

/** Unknown or missing region falls back to India's GST, the only market live before KSA. */
export const taxRegime = (region?: string | null): TaxRegime => BY_REGION[region ?? ""] ?? GST;
