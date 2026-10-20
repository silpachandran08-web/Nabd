"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "../checkout.module.css";
import { formatMoney } from "../../lib/money";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import { PAYMENT_METHODS, paymentMethodLabel } from "../../components/checkout/paymentMethods";
import AccessGate from "../../components/ui/AccessGate";
import BillingStatePill from "../../components/ui/BillingStatePill";
import StockPill, { stockErrorMessage } from "../../components/ui/StockPill";
import StatusPill from "../../components/ui/StatusPill";
import RxLinkPicker from "../../components/checkout/RxLinkPicker";
import { rxErrorMessage, unlinkedRx } from "../../components/checkout/rxLink";
import { api, ApiError, errorText, intentKeys } from "../../lib/api";
import type { Charge, Invoice, LineItem, OtcContext, RxLine } from "@nabd/api-client/types";

// NB-186: a counter sale — no patient record, no queue token (GET /billing/otc-charges, POST /billing/otc-checkout).

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

// A counter sale exists only to create an invoice (POST /billing/otc-checkout needs billing:create),
// so the whole route is gated on that grant, matching the Arrivals entry button (PHA-016). The gate
// also keeps can() below out of the server render.
export default function OtcCheckoutPage() {
  return (
    <AccessGate permission="billing:create" surface="Counter sale">
      <OtcCheckoutInner />
    </AccessGate>
  );
}

function OtcCheckoutInner() {
  const router = useRouter();
  const region = getIdentity()?.tenantRegion;
  const canCheckout = can("billing:create"); // the API requires billing:create; absent otherwise
  const canPay = can("billing:edit"); // POST /billing/invoices/{id}/payments requires billing:edit
  const canDiscount = can("billing.discount"); // REC-043: input absent, and discount not sent, without it

  const [context, setContext] = useState<OtcContext | null>(null);
  const [invoice, setInvoice] = useState<Invoice | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);

  const [items, setItems] = useState<LineItem[]>([]);
  const [category, setCategory] = useState("All");
  const [discount, setDiscount] = useState("0");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [linking, setLinking] = useState<string | null>(null); // PHL-001: chargeCode whose prescription picker is open
  const [links, setLinks] = useState<Record<string, RxLine>>({}); // chargeCode -> the signed line it is billed against

  const [method, setMethod] = useState("cash");
  const [amount, setAmount] = useState("");
  const [recording, setRecording] = useState(false);
  const [paymentError, setPaymentError] = useState<string | null>(null);

  const keys = useRef(intentKeys()); // Idempotency-Key per user intent: reused when the same sale/payment is re-sent

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setContext(await api.get<OtcContext>("/billing/otc-charges"));
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setForbidden(true);
      // PHA-010: 409 otc-not-available (pharmacy mode is external) - show the server detail.
      else if (e instanceof ApiError && e.status === 409) setError(e.detail || "Counter sales aren't available for this pharmacy mode.");
      else setError(e instanceof ApiError ? "Couldn't load charges. Try again." : "Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  function addCharge(charge: Charge) {
    setItems((prev) => {
      const existing = prev.find((i) => i.chargeCode === charge.code);
      if (existing) {
        if (charge.stockQty != null && existing.quantity >= charge.stockQty) return prev; // PHA-011: cap at stock
        return prev.map((i) => (i.chargeCode === charge.code ? { ...i, quantity: i.quantity + 1 } : i));
      }
      return [...prev, { chargeCode: charge.code, chargeName: charge.name, category: charge.category, quantity: 1, unitPrice: charge.baseAmount, taxRatePercent: charge.taxRatePercent }];
    });
    if (charge.isRx && !links[charge.code]) setLinking(charge.code); // PHL-001: adding an Rx item opens the picker
  }

  function link(code: string, line: RxLine) {
    setLinks((prev) => ({ ...prev, [code]: line }));
    setItems((prev) => prev.map((i) => (i.chargeCode === code ? { ...i, prescriptionItemId: line.prescriptionItemId } : i)));
    setLinking(null);
  }

  function setQty(code: string, qty: number) {
    const stock = context?.charges.find((c) => c.code === code)?.stockQty;
    if (stock != null && qty > stock) return; // PHA-011: cap at stock
    if (qty <= 0) {
      setItems((prev) => prev.filter((i) => i.chargeCode !== code));
      setLinks((prev) => Object.fromEntries(Object.entries(prev).filter(([k]) => k !== code)));
      if (linking === code) setLinking(null);
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

  async function submitCheckout() {
    setCreateError(null);
    if (items.length === 0) {
      setCreateError("Add at least one charge.");
      return;
    }
    setCreating(true);
    try {
      const body = {
        lineItems: items.map((i) => ({ chargeCode: i.chargeCode, chargeName: i.chargeName, category: i.category, quantity: i.quantity, unitPrice: i.unitPrice, taxRatePercent: i.taxRatePercent,
          ...(i.prescriptionItemId ? { prescriptionItemId: i.prescriptionItemId } : {}) })),
        ...(canDiscount ? { discount: discountNum } : {}),
      };
      setInvoice(await api.post<Invoice>("/billing/otc-checkout", body, { idempotencyKey: keys.current.get("sale", body) }));
      keys.current.done("sale");
    } catch (e) {
      setCreateError(e instanceof ApiError ? rxErrorMessage(e.problem) ?? stockErrorMessage(e.problem, "Couldn't generate the invoice.") : errorText(e, ""));
    } finally {
      setCreating(false);
    }
  }

  async function submitPayment(e: React.FormEvent) {
    e.preventDefault();
    setPaymentError(null);
    const amt = Number(amount);
    if (!invoice || !amt || amt <= 0) {
      setPaymentError("Enter a valid amount.");
      return;
    }
    setRecording(true);
    try {
      const body = { method, amount: amt };
      setInvoice(await api.post<Invoice>(`/billing/invoices/${invoice.id}/payments`, body, { idempotencyKey: keys.current.get(`pay:${invoice.id}`, body) }));
      keys.current.done(`pay:${invoice.id}`);
      setAmount("");
    } catch (e) {
      setPaymentError(e instanceof ApiError ? e.detail || "Couldn't record payment." : errorText(e, ""));
    } finally {
      setRecording(false);
    }
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>Loading…</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>Your role doesn&apos;t have access to checkout & billing.</div></main>;
  if (error || !context) return <main className={styles.page}><div className={styles.errorState}>{error ?? "Something went wrong."}</div></main>;

  const categories = ["All", ...Array.from(new Set(context.charges.map((c) => c.category)))];
  const visibleCharges = category === "All" ? context.charges : context.charges.filter((c) => c.category === category);

  return (
    <main className={styles.page}>
      <div className={styles.strip}>
        <div className={styles.stripIdentity}>
          <button className={styles.backBtn} onClick={() => router.push("/arrivals")}>← Arrivals</button>
          <span className={styles.stripName}>Counter sale</span>
          <span className={styles.stripMeta}>Walk-in · no patient record, no queue token</span>
        </div>
        {invoice && <BillingStatePill state={invoice.status} />}
      </div>

      {invoice ? (
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

            {invoice.status !== "paid" && canPay && (
              <form onSubmit={submitPayment}>
                <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Record payment</h2>
                <div className={styles.paymentMethods}>
                  {PAYMENT_METHODS.map((m) => (
                    <button type="button" key={m} className={method === m ? styles.methodBtnActive : styles.methodBtn} onClick={() => setMethod(m)}>{paymentMethodLabel(m, region)}</button>
                  ))}
                </div>
                <input className={styles.discountInput} style={{ width: "100%", marginBottom: "8px" }} type="number" step="0.01"
                  placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
                {paymentError && <div className={styles.errorState}>{paymentError}</div>}
                <button className={styles.recordBtn} type="submit" disabled={recording}>{recording ? "Recording…" : "Record payment"}</button>
              </form>
            )}

            {invoice.status === "paid" && (
              <button className={styles.checkoutBtn} style={{ marginTop: "16px" }} onClick={() => router.push("/checkout/otc")}>
                New counter sale
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className={styles.layout}>
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Charges</h2>
            <div className={styles.categoryTabs}>
              {categories.map((c) => (
                <button key={c} className={category === c ? styles.catTabActive : styles.catTab} onClick={() => setCategory(c)}>{c}</button>
              ))}
            </div>
            {visibleCharges.map((c) => {
              const body = (
                <>
                  <span className={styles.chargeName}>{c.name} {c.isRx && <StatusPill tone="danger" icon="alert" label="Rx" />} <StockPill qty={c.stockQty} /></span>
                  <span className={styles.chargePrice}>{formatMoney(c.baseAmount, context.currency)}</span>
                </>
              );
              // PHA-011: a 0-stock item is flagged and not offered (no button at all).
              return c.stockQty != null && c.stockQty <= 0
                ? <div key={c.id} className={styles.chargeItem} style={{ cursor: "default", opacity: 0.7 }}>{body}</div>
                : <button key={c.id} className={styles.chargeItem} onClick={() => addCharge(c)}>{body}</button>;
            })}
          </div>

          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Selected line items</h2>
            {items.length === 0 ? (
              <div className={styles.muted}>Pick a charge from the list to add it.</div>
            ) : (
              <table className={styles.table}>
                <thead><tr><th>Charge</th><th>Qty</th><th>Unit price</th><th>Tax</th><th>Line total</th><th></th></tr></thead>
                <tbody>
                  {items.map((i) => (
                    <tr key={i.chargeCode}>
                      <td>
                        {i.chargeName}<div className={styles.muted}>{i.category}</div>
                        {links[i.chargeCode] && (
                          <div className={styles.muted} data-testid={`rx-linked-${i.chargeCode}`}>
                            Rx: {links[i.chargeCode].drugName} · {links[i.chargeCode].patientName} · {links[i.chargeCode].remainingQty} left
                          </div>
                        )}
                        {waitingForRx.includes(i.chargeCode) && linking !== i.chargeCode && (
                          <button type="button" className={styles.rxLinkBtn} onClick={() => setLinking(i.chargeCode)}>Link prescription</button>
                        )}
                        {linking === i.chargeCode && (
                          <RxLinkPicker itemName={i.chargeName} onPick={(l) => link(i.chargeCode, l)} onCancel={() => setLinking(null)} />
                        )}
                      </td>
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
            <p className={styles.muted}>No patient record or queue token is created for a counter sale.</p>
            {waitingForRx.length > 0 && (
              <div className={styles.errorState} data-testid="rx-wait">Link a signed prescription to every Rx item before checkout.</div>
            )}
            {createError && <div className={styles.errorState} role="alert">{createError}</div>}
            {canCheckout && (
              <button className={styles.checkoutBtn} onClick={submitCheckout} disabled={creating || items.length === 0 || waitingForRx.length > 0}>
                {creating ? "Generating…" : "Checkout"}
              </button>
            )}
          </div>
        </div>
      )}
    </main>
  );
}
