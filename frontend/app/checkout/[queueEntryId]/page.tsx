"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../checkout.module.css";
import { formatMoney } from "../../lib/money";
import BillingStatePill from "../../components/ui/BillingStatePill";
import StockPill, { stockErrorMessage } from "../../components/ui/StockPill";
import { getIdentity } from "../../lib/session";
import { can } from "../../lib/permissions";
import { paymentMethodLabel } from "../../components/checkout/paymentMethods";
import TenderRows from "../../components/checkout/TenderRows";
import { filled, tenderError, tenderTotalMinor, toMinor, type Tender } from "../../components/checkout/tender";
import { apiFetch, goLogin } from "../../lib/apiClient";
import { checkStatus, fingerprint, pendingFor, recordPayment, submitKeyed } from "../../lib/idempotency";

// Matches GET/POST /v1/billing/checkout/{queueEntryId} and /v1/billing/invoices/{id} (CheckoutController).
type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; taxRatePercent: number; stockQty?: number | null };
type PrescribedItem = { drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null };
type CheckoutContext = {
  queueEntryId: string; patientName: string; doctorName: string; visitType: string;
  followUpEligible: boolean; currency: string; charges: Charge[]; pendingProcedures: Charge[];
  prescribedItems: PrescribedItem[]; departmentId: string; queueStatus: string;
};
type FlowStep = { stepType: string };
type LineItem = { chargeCode: string; chargeName: string; category: string; quantity: number; unitPrice: number; taxRatePercent: number; lineTotal?: number };
type Payment = { id: string; method: string; amount: number; recordedAt: string; idempotencyKey?: string | null };
type Invoice = {
  id: string; invoiceNumber: string; patientName: string; doctorName: string; currency: string;
  subtotal: number; discount: number; tax: number; roundOff: number; total: number; paid: number;
  balanceDue: number; status: string; lineItems: LineItem[]; payments: Payment[];
};
type Problem = { type?: string; title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";


// Mirrors DepartmentService.resolveStatusSequence on the backend — checked_in/waiting always
// first, checkout_pending/completed always last, with this department's configured (or, if
// unconfigured, the default vitals+consultation) steps expanded in between.
const STATUSES_FOR_STEP_TYPE: Record<string, string[]> = {
  billing: ["billing_pending"],
  vitals: ["vitals_pending", "vitals_done"],
  consultation: ["in_consult"],
  procedures: ["procedures_pending"],
};
const DEFAULT_STEP_TYPES = ["vitals", "consultation"];

function resolveSequence(steps: FlowStep[]): string[] {
  const stepTypes = steps.length > 0 ? steps.map((s) => s.stepType) : DEFAULT_STEP_TYPES;
  const sequence = ["checked_in", "waiting"];
  for (const t of stepTypes) sequence.push(...(STATUSES_FOR_STEP_TYPE[t] ?? []));
  sequence.push("checkout_pending", "completed");
  return sequence;
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

export default function CheckoutPage() {
  const router = useRouter();
  const region = getIdentity()?.tenantRegion;
  // UI gating only (absent, not disabled) — the API enforces the same grants.
  const canBill = can("billing:create");   // POST /billing/checkout/{id}
  const canPay = can("billing:edit");      // POST /billing/invoices/{id}/payments
  const canAdvance = can("queue:edit");    // PATCH /queue/{id}/status
  const canDiscount = can("billing.discount"); // REC-043: input absent, and discount not sent, without it
  const params = useParams<{ queueEntryId: string }>();
  const queueEntryId = params.queueEntryId;

  const [context, setContext] = useState<CheckoutContext | null>(null);
  const [invoice, setInvoice] = useState<Invoice | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [items, setItems] = useState<LineItem[]>([]);
  const [category, setCategory] = useState("All");
  const [discount, setDiscount] = useState("0");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [tenders, setTenders] = useState<Tender[]>([{ id: 1, method: "cash", amount: "" }]);
  const [recording, setRecording] = useState(false);
  const busyLock = useRef(false); // synchronous guard: a double-click must never start a second bill or payment
  const [paymentError, setPaymentError] = useState<string | null>(null);
  // FEF-008: "checking" while the invoice is read back after a timeout/5xx; "recorded"/"notRecorded" are its two answers.
  const [payStatus, setPayStatus] = useState<null | "checking" | "recorded" | "notRecorded">(null);

  const [flowSteps, setFlowSteps] = useState<FlowStep[]>([]);
  const [continuing, setContinuing] = useState(false);
  const [continueError, setContinueError] = useState<string | null>(null);
  const [showAddMore, setShowAddMore] = useState(false);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        goLogin();
        return null;
      }
      const res = await apiFetch(path, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        goLogin();
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const ctxRes = await authedFetch(`/billing/checkout/${queueEntryId}`);
      if (!ctxRes) return;
      if (ctxRes.status === 403) {
        setForbidden(true);
        return;
      }
      if (!ctxRes.ok) {
        setError("Couldn't load checkout details. Try again.");
        return;
      }
      const ctx: CheckoutContext = await ctxRes.json();
      setContext(ctx);
      if (ctx.pendingProcedures.length > 0) {
        setItems(ctx.pendingProcedures.map((c) => ({
          chargeCode: c.code, chargeName: c.name, category: c.category, quantity: 1,
          unitPrice: c.baseAmount, taxRatePercent: c.taxRatePercent,
        })));
      }

      const flowRes = await authedFetch(`/departments/${ctx.departmentId}/flow`);
      if (flowRes?.ok) setFlowSteps(await flowRes.json());

      const invRes = await authedFetch(`/billing/checkout/${queueEntryId}/invoice`);
      if (invRes?.status === 200) {
        const inv: Invoice = await invRes.json();
        setInvoice(inv);
        // Reload mid-timeout: a stored payment intent is checked automatically (never re-sent unasked).
        const pending = pendingFor(inv.id);
        if (pending.length > 0) {
          setPayStatus("checking");
          const out = await checkStatus(authedFetch, inv.id, pending[0].key, pending[0].scope);
          if (out.kind === "recorded") { setInvoice(out.invoice as Invoice); setPayStatus("recorded"); }
          else {
            setPayStatus("notRecorded");
            setTenders([{ id: 1, method: pending[0].method, amount: String(pending[0].amountMinor / 100) }]);
            setPaymentError(out.kind === "unknown" ? "Couldn't confirm whether that payment was recorded. Check again before collecting." : null);
          }
        }
      }
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch, queueEntryId]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  function effectivePrice(charge: Charge): number {
    const useFollowUp = context?.followUpEligible && charge.category === "Consultation" && charge.followUpAmount != null;
    return useFollowUp ? charge.followUpAmount! : charge.baseAmount;
  }

  function addCharge(charge: Charge) {
    const unitPrice = effectivePrice(charge);
    setItems((prev) => {
      const existing = prev.find((i) => i.chargeCode === charge.code);
      if (existing) {
        if (charge.stockQty != null && existing.quantity >= charge.stockQty) return prev; // PHA-011: cap at stock
        return prev.map((i) => (i.chargeCode === charge.code ? { ...i, quantity: i.quantity + 1 } : i));
      }
      return [...prev, { chargeCode: charge.code, chargeName: charge.name, category: charge.category, quantity: 1, unitPrice, taxRatePercent: charge.taxRatePercent }];
    });
  }

  function setQty(code: string, qty: number) {
    const stock = context?.charges.find((c) => c.code === code)?.stockQty;
    if (stock != null && qty > stock) return; // PHA-011: cap at stock
    if (qty <= 0) {
      setItems((prev) => prev.filter((i) => i.chargeCode !== code));
      return;
    }
    setItems((prev) => prev.map((i) => (i.chargeCode === code ? { ...i, quantity: qty } : i)));
  }

  const subtotal = round2(items.reduce((sum, i) => sum + i.unitPrice * i.quantity, 0));
  const tax = round2(items.reduce((sum, i) => sum + round2((i.unitPrice * i.quantity * i.taxRatePercent) / 100), 0));
  const discountNum = canDiscount ? Math.max(0, Number(discount) || 0) : 0;
  const rawTotal = subtotal - discountNum + tax;
  const previewTotal = Math.round(rawTotal);
  const roundOff = round2(previewTotal - rawTotal);

  // Bills the visit (creates or extends the invoice). Returns the invoice, or null when it failed.
  async function billVisit(): Promise<Invoice | null> {
    setCreateError(null);
    // An invoice already existing means this is a later billing checkpoint appending to it (or
    // closing out with nothing further to charge) — only the very first billing checkpoint for a
    // visit requires at least one item.
    if (!invoice && items.length === 0) {
      setCreateError("Add at least one charge.");
      return null;
    }
    setCreating(true);
    try {
      const body = {
        lineItems: items.map((i) => ({ chargeCode: i.chargeCode, chargeName: i.chargeName, category: i.category, quantity: i.quantity, unitPrice: i.unitPrice, taxRatePercent: i.taxRatePercent })),
        ...(canDiscount ? { discount: discountNum } : {}),
      };
      const res = await submitKeyed(authedFetch, `/billing/checkout/${queueEntryId}`, body, `co.${queueEntryId}.${fingerprint(JSON.stringify(body))}`);
      if (!res) return null;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't generate the invoice." }));
        setCreateError(stockErrorMessage(p, "Couldn't generate the invoice."));
        return null;
      }
      const inv: Invoice = await res.json();
      setInvoice(inv);
      setItems([]); // picker resets — a later billing checkpoint starts its "add more" list fresh
      setDiscount("0");
      setShowAddMore(false);
      return inv;
    } catch {
      setCreateError("Couldn't confirm the invoice. Try again: the same request is re-sent safely and cannot bill twice.");
      return null;
    } finally {
      setCreating(false);
    }
  }

  async function once(fn: () => Promise<void>) {
    if (busyLock.current) return;
    busyLock.current = true;
    try { await fn(); } finally { busyLock.current = false; }
  }

  // REC-044 split tender: rows are recorded one at a time and a recorded row is blanked, so a retry
  // after a failure can only send what has not been recorded yet. Amounts are never adjusted for
  // the cashier: anything over the balance due stops the run with a message.
  async function payTenders(start: Invoice) {
    setPaymentError(null);
    setPayStatus(null);
    setRecording(true);
    let inv = start;
    try {
      for (const row of filled(tenders)) {
        const amt = toMinor(row.amount)!;
        if (amt > Math.round(inv.balanceDue * 100)) {
          setPaymentError("That amount is more than the balance due. Adjust it and collect again.");
          return;
        }
        const out = await recordPayment(authedFetch, { invoiceId: inv.id, method: row.method, amountMinor: amt, slot: inv.payments.length }, () => setPayStatus("checking"));
        if (out.kind === "gone") return;
        if (out.kind === "offline") { setPaymentError("You are offline — payments cannot be queued"); return; }
        if (out.kind === "rejected") { setPayStatus(null); setPaymentError(out.message); return; }
        if (out.kind === "unknown") { setPaymentError("Couldn't confirm whether that payment was recorded. Check again before collecting."); setPayStatus("notRecorded"); return; }
        if (out.kind === "notRecorded") { setInvoice(out.invoice as Invoice); setPayStatus("notRecorded"); setPaymentError(out.message ?? null); return; }
        setPayStatus((cur) => (cur === "checking" ? "recorded" : null));
        const next = out.invoice as Invoice;
        setInvoice(next);
        inv = next;
        setTenders((prev) => prev.map((t) => (t.id === row.id ? { ...t, amount: "" } : t)));
      }
    } finally {
      setRecording(false);
    }
  }

  const submitCheckout = () => once(async () => { await billVisit(); });

  // Single-screen "Collect": bill the visit, then take the split payments against the new invoice.
  const collect = () => once(async () => {
    setPaymentError(null);
    if (canPay && !invoice) {
      const err = tenderError(tenders, previewTotal * 100);
      if (err) { setPaymentError(err); return; }
    }
    const inv = await billVisit();
    if (inv && canPay && filled(tenders).length > 0) await payTenders(inv);
  });

  // Invoice already exists: collect the split payments against its balance due.
  const collectOnInvoice = () => once(async () => {
    if (!invoice) return;
    const err = tenderError(tenders, Math.round(invoice.balanceDue * 100));
    if (err) { setPaymentError(err); return; }
    await payTenders(invoice);
  });

  async function continueVisit() {
    if (!context) return;
    setContinueError(null);
    setContinuing(true);
    try {
      const sequence = resolveSequence(flowSteps);
      const next = sequence[sequence.indexOf(context.queueStatus) + 1];
      if (!next) {
        setContinueError("This visit has no next step to continue to.");
        return;
      }
      const res = await authedFetch(`/queue/${queueEntryId}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: next }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't continue the visit." }));
        setContinueError(p.detail || "Couldn't continue the visit.");
        return;
      }
      router.push("/arrivals");
    } finally {
      setContinuing(false);
    }
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to checkout & billing.</div></main>;
  if (error || !context) return <main className={styles.page}><div className={styles.errorState}>{error ?? "Something went wrong."}</div></main>;

  const categories = ["All", ...Array.from(new Set(context.charges.map((c) => c.category)))];
  const visibleCharges = category === "All" ? context.charges : context.charges.filter((c) => c.category === category);

  const payStatusView = payStatus && (
    <div className={payStatus === "notRecorded" ? styles.errorState : styles.banner} role="status" data-testid="pay-status">
      {payStatus === "checking" && "Checking payment status…"}
      {payStatus === "recorded" && "Payment recorded"}
      {payStatus === "notRecorded" && "Not recorded — retry"}
    </div>
  );
  const picker = (
    <div className={styles.layout}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>Charges</h2>
        <div className={styles.categoryTabs}>
          {categories.map((c) => (
            <button key={c} className={category === c ? styles.catTabActive : styles.catTab} onClick={() => setCategory(c)}>{c}</button>
          ))}
        </div>
        {visibleCharges.map((c) => {
          const price = effectivePrice(c);
          const isFollowUp = price !== c.baseAmount;
          const body = (
            <>
              <span className={styles.chargeName}>{c.name} <StockPill qty={c.stockQty} /></span>
              <span className={styles.chargePrice}>
                {isFollowUp && <span className={styles.muted} style={{ textDecoration: "line-through", marginRight: 6 }}>{formatMoney(c.baseAmount, context.currency)}</span>}
                {formatMoney(price, context.currency)}
              </span>
            </>
          );
          // PHA-011: a 0-stock item is flagged and not offered (no button at all).
          return c.stockQty != null && c.stockQty <= 0
            ? <div key={c.id} className={styles.chargeItem} style={{ cursor: "default", opacity: 0.7 }}>{body}</div>
            : <button key={c.id} className={styles.chargeItem} onClick={() => addCharge(c)}>{body}</button>;
        })}
      </div>

      <div className={styles.card}>
        <h2 className={styles.cardTitle}>{invoice ? "New charges to add" : "Selected line items"}</h2>
        {items.length === 0 ? (
          <div className={styles.muted}>Pick a charge from the list to add it.</div>
        ) : (
          <table className={styles.table}>
            <thead><tr><th>Charge</th><th>Qty</th><th>Unit price</th><th>Tax</th><th>Line total</th><th></th></tr></thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.chargeCode}>
                  <td>{i.chargeName}<div className={styles.muted}>{i.category}</div></td>
                  <td>
                    <span className={styles.qtyStepper}>
                      <button type="button" className={styles.stepBtn} onClick={() => setQty(i.chargeCode, i.quantity - 1)}>−</button>
                      {i.quantity}
                      <button type="button" className={styles.stepBtn} onClick={() => setQty(i.chargeCode, i.quantity + 1)}>+</button>
                    </span>
                  </td>
                  <td>{formatMoney(i.unitPrice, context.currency)}</td>
                  <td>{i.taxRatePercent}%</td>
                  <td>{formatMoney(i.unitPrice * i.quantity, context.currency)}</td>
                  <td><button type="button" className={styles.removeBtn} onClick={() => setQty(i.chargeCode, 0)}>Remove</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className={styles.card}>
        <div className={styles.totals}>
          <div className={styles.totalsRow}><span>Subtotal</span><span>{formatMoney(subtotal, context.currency)}</span></div>
          {canDiscount && (
            <div className={styles.totalsRow}>
              <span>Discount</span>
              <input className={styles.discountInput} type="number" step="0.01" min="0" value={discount} onChange={(e) => setDiscount(e.target.value)} />
            </div>
          )}
          <div className={styles.totalsRow}><span>Tax</span><span>{formatMoney(tax, context.currency)}</span></div>
          <div className={styles.totalsRow}><span>Round-off</span><span>{roundOff >= 0 ? "+" : "−"}{formatMoney(Math.abs(roundOff), context.currency)}</span></div>
          <div className={styles.totalsRowMain}><span>Total</span><span>{formatMoney(previewTotal, context.currency)}</span></div>
        </div>
        <p className={styles.muted}>The total is computed from line items and can never be typed over.</p>
        {createError && <div className={styles.errorState}>{createError}</div>}
        {canPay && !invoice && (
          <>
            <TenderRows rows={tenders} onChange={setTenders} region={region} balanceMinor={previewTotal * 100} disabled={creating || recording} />
            {paymentError && <div className={styles.errorState}>{paymentError}</div>}
          </>
        )}
      </div>
    </div>
  );
  const tenderedMinor = tenderTotalMinor(tenders);
  // Sticky Collect bar. Balance due = total minus what is typed into the split rows (never negative).
  const collectBar = (
    <div className={styles.collectBar}>
      <div className={styles.collectNums}>
        <span>Total <b>{formatMoney(previewTotal, context.currency)}</b></span>
        {canPay && !invoice && <span>Balance due <b data-testid="balance-due">{formatMoney(Math.max(0, previewTotal * 100 - tenderedMinor) / 100, context.currency)}</b></span>}
      </div>
      <button className={styles.collectBtn} onClick={invoice ? submitCheckout : collect} disabled={creating || recording || (!invoice && items.length === 0)}>
        {creating || recording ? "Working…" : invoice ? "Add to invoice" : canPay && tenderedMinor > 0 ? `Collect ${formatMoney(tenderedMinor / 100, context.currency)}` : "Checkout"}
      </button>
    </div>
  );

  return (
    <main className={styles.page}>
      <div className={styles.strip}>
        <div className={styles.stripIdentity}>
          <button className={styles.backBtn} onClick={() => router.push("/arrivals")}>← Arrivals</button>
          <span className={styles.stripName}>{context.patientName}</span>
          <span className={styles.stripMeta}>{context.doctorName} · {context.visitType}</span>
        </div>
        {invoice && <BillingStatePill state={invoice.status} />}
      </div>

      {payStatusView}

      {context.followUpEligible && !invoice && (
        <div className={styles.banner}>
          Follow-up pricing available
          <span className={styles.bannerSub}>Same doctor, a completed visit within the last 14 days.</span>
        </div>
      )}

      {context.prescribedItems.length > 0 && (
        <div className={styles.banner}>
          Prescription ready: {context.prescribedItems.length} {context.prescribedItems.length === 1 ? "item" : "items"}
          <span className={styles.bannerSub}>No separate dispense screen — add matching Pharmacy charges below if in stock.</span>
        </div>
      )}

      {invoice && (
        <div className={styles.layout} style={{ gridTemplateColumns: "1fr 300px" }}>
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>{invoice.invoiceNumber}</h2>
            <table className={styles.table}>
              <thead><tr><th>Charge</th><th>Qty</th><th>Unit price</th><th>Tax %</th><th>Line total</th></tr></thead>
              <tbody>
                {invoice.lineItems.map((li) => (
                  <tr key={li.chargeCode}>
                    <td>{li.chargeName}<div className={styles.muted}>{li.category}</div></td>
                    <td>{li.quantity}</td>
                    <td>{formatMoney(li.unitPrice, invoice.currency)}</td>
                    <td>{li.taxRatePercent}</td>
                    <td>{formatMoney(li.lineTotal ?? li.unitPrice * li.quantity, invoice.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {invoice.payments.length > 0 && (
              <>
                <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Payments</h2>
                {invoice.payments.map((p) => (
                  <div key={p.id} className={styles.paymentRow}>
                    <span>{paymentMethodLabel(p.method, region)}</span>
                    <span>{formatMoney(p.amount, invoice.currency)}</span>
                  </div>
                ))}
              </>
            )}
            {!showAddMore && canBill && invoice.status !== "void" && (
              <button type="button" className={styles.backBtn} style={{ marginTop: "16px" }} onClick={() => setShowAddMore(true)}>
                + Add more charges
              </button>
            )}
          </div>

          <div className={styles.card}>
            <div className={styles.totals}>
              <div className={styles.totalsRow}><span>Subtotal</span><span>{formatMoney(invoice.subtotal, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>Discount</span><span>−{formatMoney(invoice.discount, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>Tax</span><span>{formatMoney(invoice.tax, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>Round-off</span><span>{invoice.roundOff >= 0 ? "+" : "−"}{formatMoney(Math.abs(invoice.roundOff), invoice.currency)}</span></div>
              <div className={styles.totalsRowMain}><span>Total</span><span>{formatMoney(invoice.total, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>Paid</span><span>{formatMoney(invoice.paid, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>Balance due</span><span>{formatMoney(invoice.balanceDue, invoice.currency)}</span></div>
            </div>

            {context.queueStatus === "billing_pending" && canAdvance && (
              <>
                <p className={styles.muted} style={{ marginTop: "12px" }}>
                  This is an interim billing stop — the visit continues after this.
                </p>
                {continueError && <div className={styles.errorState}>{continueError}</div>}
                <button className={styles.checkoutBtn} onClick={continueVisit} disabled={continuing}>
                  {continuing ? "Continuing…" : "Continue visit →"}
                </button>
              </>
            )}

            {context.queueStatus === "checkout_pending" && canBill && invoice.status !== "void" && (
              <>
                <p className={styles.muted} style={{ marginTop: "12px" }}>
                  {invoice.balanceDue > 0 ? "Record the remaining payment, then complete checkout." : "Nothing further to charge for this visit."}
                </p>
                {createError && <div className={styles.errorState}>{createError}</div>}
                <button className={styles.checkoutBtn} onClick={submitCheckout} disabled={creating}>
                  {creating ? "Completing…" : "Complete checkout"}
                </button>
              </>
            )}

            {invoice.status !== "paid" && invoice.status !== "void" && canPay && (
              <>
                <TenderRows rows={tenders} onChange={setTenders} region={region} balanceMinor={Math.round(invoice.balanceDue * 100)} disabled={creating || recording} />
                {paymentError && <div className={styles.errorState}>{paymentError}</div>}
                <button className={styles.recordBtn} onClick={collectOnInvoice} disabled={creating || recording || tenderTotalMinor(tenders) === 0}>
                  {recording ? "Recording…" : `Collect ${formatMoney(tenderTotalMinor(tenders) / 100, invoice.currency)}`}
                </button>
              </>
            )}
          </div>
        </div>
      )}

      {!canBill && !invoice && <div className={styles.state}>No invoice has been generated for this visit yet.</div>}
      {canBill && (!invoice || showAddMore) && <>{picker}{collectBar}</>}
    </main>
  );
}
