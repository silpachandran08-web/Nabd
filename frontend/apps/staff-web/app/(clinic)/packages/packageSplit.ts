// FIN-025: labels for the per-component split. No imports, so the unit tests load it directly.

/** How a component's tax reads in the sale summary: exempt lines carry none; the amounts are the server's, in minor units. */
export function taxNote(taxCategory: string, taxMinor: number): string {
  if (taxCategory === "EXEMPT") return "exempt";
  return taxMinor > 0 ? "taxable" : "no tax";
}

/** What one refund line gives back (taxable value plus the tax reversed on it), in minor units. */
export const refundLineMinor = (l: { refundTaxableMinor: number; refundTaxMinor: number }): number => l.refundTaxableMinor + l.refundTaxMinor;
