"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../checkout.module.css";
import { formatMoney, paymentMethodLabel } from "@nabd/domain";
import BillingStatePill from "../../../components/ui/BillingStatePill";
import StockPill, { stockErrorMessage } from "../../../components/ui/StockPill";
import StatusPill from "../../../components/ui/StatusPill";
import { autoLink, rxErrorMessage, unlinkedRx } from "../../../components/checkout/rxLink";
import { getIdentity } from "../../../lib/session";
import { can } from "../../../lib/permissions";
import TenderRows from "../../../components/checkout/TenderRows";
import { filled, tenderError, tenderTotalMinor, toMinor, type Tender } from "../../../components/checkout/tender";
import { api, ApiError, errorText, intentKeys } from "../../../lib/api";
import type { Charge, CheckoutContext, FlowStep, Invoice, LineItem } from "@nabd/api-client/types";
import { PRICE_REASONS, reasonLabel } from "../../../components/checkout/priceReasons";


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
  const [discountReason, setDiscountReason] = useState("");
  const [editing, setEditing] = useState<string | null>(null); // chargeCode whose override editor is open
  const [ovPrice, setOvPrice] = useState("");
  const [ovReason, setOvReason] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  const [tenders, setTenders] = useState<Tender[]>([{ id: 1, method: "cash", amount: "" }]);
  const [recording, setRecording] = useState(false);
  const busyLock = useRef(false); // synchronous guard: a double-click must never start a second bill or payment
  const [paymentError, setPaymentError] = useState<string | null>(null);

  const [flowSteps, setFlowSteps] = useState<FlowStep[]>([]);
  const [continuing, setContinuing] = useState(false);
  const [continueError, setContinueError] = useState<string | null>(null);
  const [showAddMore, setShowAddMore] = useState(false);

  const keys = useRef(intentKeys()); // Idempotency-Key per user intent: reused on a retry of the same bill/payment/step

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const ctx = await api.get<CheckoutContext>(`/billing/checkout/${queueEntryId}`);
      setContext(ctx);
      if (ctx.pendingProcedures.length > 0) {
        setItems(ctx.pendingProcedures.map((c) => ({
          chargeCode: c.code, chargeName: c.name, category: c.category, quantity: 1,
          unitPrice: c.baseAmount, taxRatePercent: c.taxRatePercent,
        })));
      }
      // Best-effort reads: a failure leaves the defaults (no flow steps / no invoice yet).
      setFlowSteps(await api.get<FlowStep[]>(`/departments/${ctx.departmentId}/flow`).catch(() => []));
      const inv = await api.get<Invoice>(`/billing/checkout/${queueEntryId}/invoice`).catch(() => null);
      if (inv) setInvoice(inv);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setForbidden(true);
      else setError(e instanceof ApiError ? "Couldn't load checkout details. Try again." : "Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, [queueEntryId]);

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
      // PHL-001: an Rx item is pre-linked to the visit's signed line for it; otherwise the row asks for one.
      const prescriptionItemId = charge.isRx ? autoLink(charge.name, context?.prescribedItems ?? []) ?? undefined : undefined;
      return [...prev, { chargeCode: charge.code, chargeName: charge.name, category: charge.category, quantity: 1, unitPrice, taxRatePercent: charge.taxRatePercent, prescriptionItemId }];
    });
  }

  function linkRx(code: string, prescriptionItemId: string) {
    setItems((prev) => prev.map((i) => (i.chargeCode === code ? { ...i, prescriptionItemId } : i)));
  }

  function applyOverride(code: string) {
    const price = Number(ovPrice);
    if (ovPrice === "" || !Number.isFinite(price) || price < 0 || !ovReason) return;
    setItems((prev) => prev.map((i) => (i.chargeCode === code ? { ...i, unitPrice: price, override: { unitPrice: price, reasonCode: ovReason } } : i)));
    setEditing(null);
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
  const waitingForRx = unlinkedRx(items, context?.charges ?? []);
  const rxChoices = (context?.prescribedItems ?? []).filter((p) => p.remainingQty > 0);

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
    if (discountNum > 0 && !discountReason) {
      setCreateError("Pick a reason for the discount.");
      return null;
    }
    setCreating(true);
    try {
      const body = {
        // codes only: the server prices every line from the catalogue (FIN-003)
        lineItems: items.map((i) => ({ chargeCode: i.chargeCode, quantity: i.quantity, ...(canDiscount && i.override ? { override: i.override } : {}),
          ...(i.prescriptionItemId ? { prescriptionItemId: i.prescriptionItemId } : {}) })),
        ...(canDiscount && discountNum > 0 ? { discounts: [{ kind: "amount", value: discountNum, reasonCode: discountReason }] } : {}),
      };
      const inv = await api.post<Invoice>(`/billing/checkout/${queueEntryId}`, body, { idempotencyKey: keys.current.get("bill", body) });
      keys.current.done("bill");
      setInvoice(inv);
      setItems([]); // picker resets — a later billing checkpoint starts its "add more" list fresh
      setDiscount("0");
      setDiscountReason("");
      setShowAddMore(false);
      return inv;
    } catch (e) {
      // A server answer is a refusal; no answer (or a timeout) leaves the outcome unknown.
      setCreateError(e instanceof ApiError ? rxErrorMessage(e.problem) ?? stockErrorMessage(e.problem, e.detail || "Couldn't generate the invoice.") : "Couldn't confirm the invoice. Reload this page to see whether it was created before trying again.");
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
    setRecording(true);
    let inv = start;
    try {
      for (const row of filled(tenders)) {
        const amt = toMinor(row.amount)!;
        if (amt > Math.round(inv.balanceDue * 100)) {
          setPaymentError("That amount is more than the balance due. Adjust it and collect again.");
          return;
        }
        const before = inv.payments.length;
        let next: Invoice;
        try {
          const body = { method: row.method, amount: amt / 100 };
          next = await api.post<Invoice>(`/billing/invoices/${inv.id}/payments`, body, { idempotencyKey: keys.current.get(`pay:${inv.id}`, body) });
          keys.current.done(`pay:${inv.id}`);
        } catch (e) {
          if (e instanceof ApiError) {
            setPaymentError(e.detail || "Couldn't record payment.");
            if (e.status === 422) { // FIN-001: overpayment names the balance; refresh the one shown
              const r = await api.get<Invoice>(`/billing/checkout/${queueEntryId}/invoice`).catch(() => null);
              if (r) setInvoice(r);
            }
            return;
          }
          // Outcome unknown: read the invoice back instead of guessing, so a payment that landed is never re-sent.
          const r = await api.get<Invoice>(`/billing/checkout/${queueEntryId}/invoice`).catch(() => null);
          if (!r) {
            setPaymentError("Couldn't confirm whether that payment was recorded. Reload this page and check the Payments list before collecting again.");
            return;
          }
          next = r;
          if (next.payments.length <= before) {
            setInvoice(next);
            setPaymentError("That payment did not go through and nothing was recorded. Try again.");
            return;
          }
          keys.current.done(`pay:${inv.id}`);
        }
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
      await api.patch(`/queue/${queueEntryId}/status`, { status: next, expectedStatus: context.queueStatus }, { idempotencyKey: keys.current.get("advance", next) });
      router.push("/arrivals");
    } catch (e) {
      setContinueError(errorText(e, "Couldn't continue the visit."));
    } finally {
      setContinuing(false);
    }
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to checkout & billing.</div></main>;
  if (error || !context) return <main className={styles.page}><div className={styles.errorState}>{error ?? "Something went wrong."}</div></main>;

  const categories = ["All", ...Array.from(new Set(context.charges.map((c) => c.category)))];
  const visibleCharges = category === "All" ? context.charges : context.charges.filter((c) => c.category === category);

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
              <span className={styles.chargeName}>{c.name} {c.isRx && <StatusPill tone="danger" label="Rx" />} <StockPill qty={c.stockQty} /></span>
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
            <thead><tr><th scope="col">Charge</th><th scope="col">Qty</th><th scope="col">Unit price</th><th scope="col">Tax</th><th scope="col">Line total</th><th scope="col"></th></tr></thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.chargeCode}>
                  <td>
                    {i.chargeName}<div className={styles.muted}>{i.category}</div>
                    {/* PHL-001: an Rx line is linked to one signed line of this visit's prescription; no "unlink" choice once linked */}
                    {(i.prescriptionItemId || waitingForRx.includes(i.chargeCode)) && (
                      <select aria-label={`Prescription line for ${i.chargeName}`} className={styles.rxPickerInput} value={i.prescriptionItemId ?? ""}
                        onChange={(e) => e.target.value && linkRx(i.chargeCode, e.target.value)}>
                        {!i.prescriptionItemId && <option value="">{rxChoices.length ? "Choose prescription line…" : "No signed prescription line left"}</option>}
                        {(context.prescribedItems ?? []).filter((p) => p.remainingQty > 0 || p.prescriptionItemId === i.prescriptionItemId).map((p) => (
                          <option key={p.prescriptionItemId} value={p.prescriptionItemId}>Rx: {p.drugName}{p.dosage ? ` ${p.dosage}` : ""} · {p.remainingQty} left</option>
                        ))}
                      </select>
                    )}
                  </td>
                  <td>
                    <span className={styles.qtyStepper}>
                      <button type="button" className={styles.stepBtn} onClick={() => setQty(i.chargeCode, i.quantity - 1)}>−</button>
                      {i.quantity}
                      <button type="button" className={styles.stepBtn} onClick={() => setQty(i.chargeCode, i.quantity + 1)}>+</button>
                    </span>
                  </td>
                  <td>
                    {formatMoney(i.unitPrice, context.currency)}
                    {i.override && <div className={styles.muted}>Override: {reasonLabel(i.override.reasonCode)}</div>}
                    {canDiscount && editing !== i.chargeCode && (
                      <button type="button" className={styles.removeBtn} onClick={() => { setEditing(i.chargeCode); setOvPrice(String(i.unitPrice)); setOvReason(i.override?.reasonCode ?? ""); }}>Override price</button>
                    )}
                    {canDiscount && editing === i.chargeCode && (
                      <div>
                        <input aria-label="Override unit price" className={styles.discountInput} type="number" step="0.01" min="0" value={ovPrice} onChange={(e) => setOvPrice(e.target.value)} />
                        <select aria-label="Override reason" className={styles.discountInput} value={ovReason} onChange={(e) => setOvReason(e.target.value)}>
                          <option value="">Reason…</option>
                          {PRICE_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
                        </select>
                        <button type="button" className={styles.removeBtn} disabled={!ovReason || ovPrice === ""} onClick={() => applyOverride(i.chargeCode)}>Apply</button>
                        <button type="button" className={styles.removeBtn} onClick={() => setEditing(null)}>Cancel</button>
                      </div>
                    )}
                  </td>
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
              <input aria-label="Discount amount" className={styles.discountInput} type="number" step="0.01" min="0" value={discount} onChange={(e) => setDiscount(e.target.value)} />
            </div>
          )}
          {canDiscount && Number(discount) > 0 && (
            <div className={styles.totalsRow}>
              <span>Discount reason</span>
              <select aria-label="Discount reason" className={styles.discountInput} value={discountReason} onChange={(e) => setDiscountReason(e.target.value)}>
                <option value="">Reason…</option>
                {PRICE_REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
            </div>
          )}
          <div className={styles.totalsRow}><span>Tax</span><span>{formatMoney(tax, context.currency)}</span></div>
          <div className={styles.totalsRow}><span>Round-off</span><span>{roundOff >= 0 ? "+" : "−"}{formatMoney(Math.abs(roundOff), context.currency)}</span></div>
          <div className={styles.totalsRowMain}><span>Total</span><span>{formatMoney(previewTotal, context.currency)}</span></div>
        </div>
        <p className={styles.muted}>The total is computed from line items and can never be typed over.</p>
        {waitingForRx.length > 0 && (
          <div className={styles.errorState} data-testid="rx-wait">Link every Rx item to a line of the signed prescription before checkout.</div>
        )}
        {createError && <div className={styles.errorState} role="alert">{createError}</div>}
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
      <button className={styles.collectBtn} onClick={invoice ? submitCheckout : collect} disabled={creating || recording || (!invoice && items.length === 0) || waitingForRx.length > 0}>
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

      {context.followUpEligible && !invoice && (
        <div className={styles.banner}>
          Follow-up pricing available
          <span className={styles.bannerSub}>Same doctor, a completed visit within the last 14 days.</span>
        </div>
      )}

      {context.prescribedItems.length > 0 && (
        <div className={styles.banner}>
          Prescription ready: {context.prescribedItems.length} {context.prescribedItems.length === 1 ? "item" : "items"}
          <span className={styles.bannerSub}>No separate dispense screen — add matching Pharmacy charges below if in stock. Rx items link to these lines.</span>
        </div>
      )}

      {invoice && (
        <div className={styles.layout} style={{ gridTemplateColumns: "1fr 300px" }}>
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>{invoice.invoiceNumber}</h2>
            <table className={styles.table}>
              <thead><tr><th scope="col">Charge</th><th scope="col">Qty</th><th scope="col">Unit price</th><th scope="col">Tax %</th><th scope="col">Line total</th></tr></thead>
              <tbody>
                {invoice.lineItems.map((li) => (
                  <tr key={li.chargeCode}>
                    <td>{li.chargeName}<div className={styles.muted}>{li.category}</div></td>
                    <td>{li.quantity}</td>
                    <td>
                      {formatMoney(li.unitPrice, invoice.currency)}
                      {li.overrideReason && li.listUnitPrice != null && (
                        <div className={styles.muted}>List {formatMoney(li.listUnitPrice, invoice.currency)} · {reasonLabel(li.overrideReason)}</div>
                      )}
                    </td>
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
