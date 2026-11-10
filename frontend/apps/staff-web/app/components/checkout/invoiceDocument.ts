// FEF-047: the invoice document model (GET /v1/billing/invoices/{id}/document, owned by Core billing, FIN-012) and the
// ONE pure function that turns it into the text both print layouts show. Amounts are integer minor units.
import { amountInWords } from "@nabd/i18n/format";
import { formatMoney } from "@nabd/i18n/format";
import { taxRegime, type TaxRegime } from "@nabd/domain";

export type DocLine = {
  description: string; sac: string | null; quantity: number; unitMinor: number;
  taxableMinor: number; exempt: boolean; cgstRate: number; cgstMinor: number; sgstRate: number; sgstMinor: number; igstRate: number; igstMinor: number; totalMinor: number;
};
export type DocPayment = { method: string; reference: string | null; amountMinor: number; paidAt: string };
export type InvoiceDocument = {
  invoiceNumber: string; issuedAt: string; currency: string; placeOfSupply: string | null;
  seller: { name: string; gstin: string | null; address: string | null; phone: string | null };
  buyer: { name: string; mrn: string | null; gstin: string | null };
  lines: DocLine[];
  totals: { taxableMinor: number; exemptMinor: number; cgstMinor: number; sgstMinor: number; igstMinor: number; discountMinor: number; roundOffMinor: number; totalMinor: number; paidMinor: number; balanceMinor: number };
  amountInWords?: string | null; payments: DocPayment[]; reprintCount: number; copy: "original" | "duplicate"; reprintedAt?: string | null;
};

/** The totals must add up to the paisa; a document that does not is refused instead of printed. Returns the first problem, or null. */
export function documentProblem(d: InvoiceDocument): string | null {
  const sum = (f: (l: DocLine) => number) => d.lines.reduce((a, l) => a + f(l), 0);
  const t = d.totals;
  if (sum((l) => l.cgstMinor) !== t.cgstMinor || sum((l) => l.sgstMinor) !== t.sgstMinor || sum((l) => l.igstMinor) !== t.igstMinor) return "tax";
  if (sum((l) => (l.exempt ? 0 : l.taxableMinor)) !== t.taxableMinor || sum((l) => (l.exempt ? l.taxableMinor : 0)) !== t.exemptMinor) return "taxable";
  if (t.taxableMinor + t.exemptMinor + t.cgstMinor + t.sgstMinor + t.igstMinor - t.discountMinor + t.roundOffMinor !== t.totalMinor) return "total";
  if (t.totalMinor - t.paidMinor !== t.balanceMinor) return "balance";
  return null;
}

export type InvoiceView = {
  title: string; copyNote: string | null; number: string; date: string; placeOfSupply: string | null; seller: InvoiceDocument["seller"]; buyer: InvoiceDocument["buyer"];
  rows: { description: string; sac: string; qty: string; kind: "Exempt" | "Taxable"; taxable: string; tax: string; total: string }[];
  totals: { label: string; value: string; strong?: boolean }[]; words: string;
  payments: { label: string; value: string }[];
};

/** `when` formats an ISO instant in the clinic zone (caller passes the clinic formatter, so this stays pure and testable).
 * FEF-040: tax labels follow the market (`regime`): India splits GST into CGST/SGST/IGST, a VAT market shows one VAT line. */
export function buildInvoiceView(d: InvoiceDocument, when: (iso: string) => string, regime: TaxRegime = taxRegime("IN")): InvoiceView {
  const m = (n: number) => formatMoney(n, d.currency, { minor: true });
  const rate = (l: DocLine) => (l.exempt ? "—" : !regime.split ? `${regime.label} ${l.cgstRate + l.sgstRate + l.igstRate}%` : l.igstRate ? `IGST ${l.igstRate}%` : `CGST ${l.cgstRate}% + SGST ${l.sgstRate}%`);
  const t = d.totals;
  const taxSum = t.cgstMinor + t.sgstMinor + t.igstMinor;
  const taxRows = (regime.split ? [
    t.cgstMinor ? { label: "CGST", value: m(t.cgstMinor) } : null,
    t.sgstMinor ? { label: "SGST", value: m(t.sgstMinor) } : null,
    t.igstMinor ? { label: "IGST", value: m(t.igstMinor) } : null,
  ] : [taxSum ? { label: regime.label, value: m(taxSum) } : null]).filter((r): r is { label: string; value: string } => r !== null);
  return {
    title: "Tax invoice",
    copyNote: d.copy === "duplicate" ? `Duplicate copy${d.reprintedAt ? ` · reprinted ${when(d.reprintedAt)}` : ""}` : null,
    number: d.invoiceNumber, date: when(d.issuedAt), placeOfSupply: d.placeOfSupply, seller: d.seller, buyer: d.buyer,
    rows: d.lines.map((l) => ({
      description: l.description, sac: l.sac ?? "—", qty: String(l.quantity), kind: l.exempt ? "Exempt" : "Taxable",
      taxable: m(l.taxableMinor), tax: rate(l) + (l.exempt ? "" : ` = ${m(l.cgstMinor + l.sgstMinor + l.igstMinor)}`), total: m(l.totalMinor),
    })),
    totals: [
      { label: "Taxable value", value: m(t.taxableMinor) },
      ...(t.exemptMinor ? [{ label: "Exempt value", value: m(t.exemptMinor) }] : []),
      ...taxRows,
      ...(t.discountMinor ? [{ label: "Discount", value: `-${m(t.discountMinor)}` }] : []),
      ...(t.roundOffMinor ? [{ label: "Round-off", value: `${t.roundOffMinor < 0 ? "-" : "+"}${m(Math.abs(t.roundOffMinor))}` }] : []),
      { label: "Total", value: m(t.totalMinor), strong: true },
      { label: "Paid", value: m(t.paidMinor) },
      { label: "Balance due", value: m(t.balanceMinor) },
    ],
    words: d.amountInWords || amountInWords(t.totalMinor),
    payments: d.payments.map((p) => ({ label: `${p.method}${p.reference ? ` · ${p.reference}` : ""} · ${when(p.paidAt)}`, value: m(p.amountMinor) })),
  };
}
