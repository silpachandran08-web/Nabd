// FIN-025: labels for the per-component split. No imports, so the unit tests load it directly.

/** How a component's tax reads in the sale summary: exempt lines carry none; the amounts are the server's, in minor units. */
export function taxNote(taxCategory: string, taxMinor: number): string {
  if (taxCategory === "EXEMPT") return "exempt";
  return taxMinor > 0 ? "taxable" : "no tax";
}

/** What one refund line gives back (taxable value plus the tax reversed on it), in minor units. */
export const refundLineMinor = (l: { refundTaxableMinor: number; refundTaxMinor: number }): number => l.refundTaxableMinor + l.refundTaxMinor;

/** The GST opinion's decision-log reference as the server accepts it (PackageSettingsWriteRequest: empty, or DEC-… / T-…). */
const DECISION_ID = /^(DEC|T)-[A-Za-z0-9-]{1,60}$/;
export const DECISION_ID_HELP = "Use the decision-log reference, like DEC-2026-014 or T-213.";
/** null when the id may be sent (empty clears it), else what to type. */
export const decisionIdProblem = (value: string): string | null => (value.trim() === "" || DECISION_ID.test(value.trim()) ? null : DECISION_ID_HELP);
