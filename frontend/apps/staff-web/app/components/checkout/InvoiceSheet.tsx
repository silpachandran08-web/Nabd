import { PrintFooter, PrintHeader, PrintSheet, PrintTotals, type Paper } from "@nabd/ui";
import type { InvoiceView } from "./invoiceDocument";

// FEF-047: GST tax invoice (A4/A5/80 mm) and receipt (80 mm by default), built on the FEF-046 print kit.
// Every string comes from buildInvoiceView, so what prints is exactly what the golden tests check.
export function InvoiceSheet({ view, paper, kind }: { view: InvoiceView; paper: Paper; kind: "invoice" | "receipt" }) {
  const receipt = kind === "receipt";
  const regs = [view.seller.gstin ? { label: "GSTIN", value: view.seller.gstin } : null].filter((r): r is { label: string; value: string } => r !== null);
  return (
    <PrintSheet paper={paper} label={receipt ? "Receipt" : "Tax invoice"}>
      <PrintHeader clinic={view.seller.name} phone={view.seller.phone} registrations={regs} />
      {view.seller.address && <div>{view.seller.address}</div>}
      <h1 className="nbp-h" style={{ marginBlockStart: "3mm" }}>{receipt ? "Receipt" : view.title}</h1>
      {view.copyNote && <div role="note"><strong>{view.copyNote}</strong></div>}
      <div className="nbp-row"><span>No. {view.number}</span><span>{view.date}</span></div>
      <div>Patient: <bdi>{view.buyer.name}</bdi>{view.buyer.mrn ? ` · MRN ${view.buyer.mrn}` : ""}</div>
      {view.buyer.gstin && <div>Buyer GSTIN: {view.buyer.gstin}</div>}
      {!receipt && view.placeOfSupply && <div>Place of supply: {view.placeOfSupply}</div>}
      {!receipt && (
        <table className="nbp-totals" style={{ marginBlock: "3mm" }}>
          <thead><tr><td>Item (SAC)</td><td>Amount</td></tr></thead>
          <tbody>
            {view.rows.map((r, i) => (
              <tr key={i}>
                <td>{r.description} ({r.sac}) × {r.qty}<br />{r.kind}: {r.taxable}{r.kind === "Taxable" ? ` · ${r.tax}` : ""}</td>
                <td>{r.total}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <PrintTotals rows={receipt ? view.totals.filter((t) => ["Total", "Paid", "Balance due"].includes(t.label)) : view.totals} />
      {view.payments.length > 0 && (
        <section aria-label="Payments" style={{ marginBlockStart: "2mm" }}>
          {view.payments.map((p, i) => <div key={i} className="nbp-row"><span>{p.label}</span><span>{p.value}</span></div>)}
        </section>
      )}
      {!receipt && <PrintFooter>Amount in words: {view.words}</PrintFooter>}
      <PrintFooter>Computer-generated document.</PrintFooter>
    </PrintSheet>
  );
}
