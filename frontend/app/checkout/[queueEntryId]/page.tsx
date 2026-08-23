"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../checkout.module.css";

// Matches GET/POST /v1/billing/checkout/{queueEntryId} and /v1/billing/invoices/{id} (CheckoutController).
type Charge = { id: string; code: string; name: string; category: string; baseAmount: number; followUpAmount: number | null; taxRatePercent: number };
type PrescribedItem = { drugName: string; dosage: string | null; frequency: string | null; duration: string | null; instructions: string | null };
type CheckoutContext = {
  queueEntryId: string; patientName: string; doctorName: string; visitType: string;
  followUpEligible: boolean; currency: string; charges: Charge[]; pendingProcedures: Charge[];
  prescribedItems: PrescribedItem[];
};
type LineItem = { chargeCode: string; chargeName: string; category: string; quantity: number; unitPrice: number; taxRatePercent: number; lineTotal?: number };
type Payment = { id: string; method: string; amount: number; recordedAt: string };
type Invoice = {
  id: string; invoiceNumber: string; patientName: string; doctorName: string; currency: string;
  subtotal: number; discount: number; tax: number; roundOff: number; total: number; paid: number;
  balanceDue: number; status: string; lineItems: LineItem[]; payments: Payment[];
};
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

const STATUS_PILL: Record<string, string> = { paid: styles.pillPaid, partial: styles.pillPartial, unpaid: styles.pillUnpaid };

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

export default function CheckoutPage() {
  const router = useRouter();
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

  const [method, setMethod] = useState("cash");
  const [amount, setAmount] = useState("");
  const [recording, setRecording] = useState(false);
  const [paymentError, setPaymentError] = useState<string | null>(null);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
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

      const invRes = await authedFetch(`/billing/checkout/${queueEntryId}/invoice`);
      if (invRes?.status === 200) {
        setInvoice(await invRes.json());
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

  function addCharge(charge: Charge) {
    const useFollowUp = context?.followUpEligible && charge.category === "Consultation" && charge.followUpAmount != null;
    const unitPrice = useFollowUp ? charge.followUpAmount! : charge.baseAmount;
    setItems((prev) => {
      const existing = prev.find((i) => i.chargeCode === charge.code);
      if (existing) {
        return prev.map((i) => (i.chargeCode === charge.code ? { ...i, quantity: i.quantity + 1 } : i));
      }
      return [...prev, { chargeCode: charge.code, chargeName: charge.name, category: charge.category, quantity: 1, unitPrice, taxRatePercent: charge.taxRatePercent }];
    });
  }

  function setQty(code: string, qty: number) {
    if (qty <= 0) {
      setItems((prev) => prev.filter((i) => i.chargeCode !== code));
      return;
    }
    setItems((prev) => prev.map((i) => (i.chargeCode === code ? { ...i, quantity: qty } : i)));
  }

  const subtotal = round2(items.reduce((sum, i) => sum + i.unitPrice * i.quantity, 0));
  const tax = round2(items.reduce((sum, i) => sum + round2((i.unitPrice * i.quantity * i.taxRatePercent) / 100), 0));
  const discountNum = Math.max(0, Number(discount) || 0);
  const rawTotal = subtotal - discountNum + tax;
  const previewTotal = Math.round(rawTotal);
  const roundOff = round2(previewTotal - rawTotal);

  async function submitCheckout() {
    setCreateError(null);
    if (items.length === 0) {
      setCreateError("Add at least one charge.");
      return;
    }
    setCreating(true);
    try {
      const res = await authedFetch(`/billing/checkout/${queueEntryId}`, {
        method: "POST",
        body: JSON.stringify({
          lineItems: items.map((i) => ({ chargeCode: i.chargeCode, chargeName: i.chargeName, category: i.category, quantity: i.quantity, unitPrice: i.unitPrice, taxRatePercent: i.taxRatePercent })),
          discount: discountNum,
        }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't generate the invoice." }));
        setCreateError(p.detail || "Couldn't generate the invoice.");
        return;
      }
      setInvoice(await res.json());
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
      const res = await authedFetch(`/billing/invoices/${invoice.id}/payments`, {
        method: "POST",
        body: JSON.stringify({ method, amount: amt }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't record payment." }));
        setPaymentError(p.detail || "Couldn't record payment.");
        return;
      }
      setInvoice(await res.json());
      setAmount("");
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
          <span className={styles.stripName}>{context.patientName}</span>
          <span className={styles.stripMeta}>{context.doctorName} · {context.visitType}</span>
        </div>
        {invoice && <span className={`${styles.pill} ${STATUS_PILL[invoice.status] ?? styles.pillNeutral}`}>{invoice.status}</span>}
      </div>

      {context.followUpEligible && !invoice && (
        <div className={styles.banner}>
          Follow-up pricing available
          <span className={styles.bannerSub}>Same doctor, a completed visit within the last 14 days.</span>
        </div>
      )}

      {context.prescribedItems.length > 0 && (
        <div className={styles.banner}>
          Prescribed: {context.prescribedItems.map((i) => i.drugName).join(", ")}
          <span className={styles.bannerSub}>No separate dispense screen — add matching Pharmacy charges below if in stock.</span>
        </div>
      )}

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
                    <td>{li.unitPrice.toFixed(2)}</td>
                    <td>{li.taxRatePercent}</td>
                    <td>{(li.lineTotal ?? li.unitPrice * li.quantity).toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {invoice.payments.length > 0 && (
              <>
                <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Payments</h2>
                {invoice.payments.map((p) => (
                  <div key={p.id} className={styles.paymentRow}>
                    <span>{p.method}</span>
                    <span>{invoice.currency} {p.amount.toFixed(2)}</span>
                  </div>
                ))}
              </>
            )}
          </div>

          <div className={styles.card}>
            <div className={styles.totals}>
              <div className={styles.totalsRow}><span>Subtotal</span><span>{invoice.subtotal.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Discount</span><span>-{invoice.discount.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Tax</span><span>{invoice.tax.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Round-off</span><span>{invoice.roundOff >= 0 ? "+" : ""}{invoice.roundOff.toFixed(2)}</span></div>
              <div className={styles.totalsRowMain}><span>Total</span><span>{invoice.currency} {invoice.total.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Paid</span><span>{invoice.paid.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Balance due</span><span>{invoice.balanceDue.toFixed(2)}</span></div>
            </div>

            {invoice.status !== "paid" && (
              <form onSubmit={submitPayment}>
                <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>Record payment</h2>
                <div className={styles.paymentMethods}>
                  {["cash", "card", "upi", "other"].map((m) => (
                    <button type="button" key={m} className={method === m ? styles.methodBtnActive : styles.methodBtn} onClick={() => setMethod(m)}>{m}</button>
                  ))}
                </div>
                <input className={styles.discountInput} style={{ width: "100%", marginBottom: "8px" }} type="number" step="0.01"
                  placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} />
                {paymentError && <div className={styles.errorState}>{paymentError}</div>}
                <button className={styles.recordBtn} type="submit" disabled={recording}>{recording ? "Recording…" : "Record payment"}</button>
              </form>
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
            {visibleCharges.map((c) => (
              <button key={c.id} className={styles.chargeItem} onClick={() => addCharge(c)}>
                <span className={styles.chargeName}>{c.name}</span>
                <span className={styles.chargePrice}>{context.currency} {c.baseAmount.toFixed(2)}</span>
              </button>
            ))}
          </div>

          <div className={styles.card}>
            <h2 className={styles.cardTitle}>Selected line items</h2>
            {items.length === 0 ? (
              <div className={styles.muted}>Pick a charge from the left.</div>
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
                      <td>{i.unitPrice.toFixed(2)}</td>
                      <td>{i.taxRatePercent}%</td>
                      <td>{(i.unitPrice * i.quantity).toFixed(2)}</td>
                      <td><button type="button" className={styles.removeBtn} onClick={() => setQty(i.chargeCode, 0)}>Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div className={styles.card}>
            <div className={styles.totals}>
              <div className={styles.totalsRow}><span>Subtotal</span><span>{subtotal.toFixed(2)}</span></div>
              <div className={styles.totalsRow}>
                <span>Discount</span>
                <input className={styles.discountInput} type="number" step="0.01" min="0" value={discount} onChange={(e) => setDiscount(e.target.value)} />
              </div>
              <div className={styles.totalsRow}><span>Tax</span><span>{tax.toFixed(2)}</span></div>
              <div className={styles.totalsRow}><span>Round-off</span><span>{roundOff >= 0 ? "+" : ""}{roundOff.toFixed(2)}</span></div>
              <div className={styles.totalsRowMain}><span>Total</span><span>{context.currency} {previewTotal.toFixed(2)}</span></div>
            </div>
            <p className={styles.muted}>The total is computed from line items and can never be typed over.</p>
            {createError && <div className={styles.errorState}>{createError}</div>}
            <button className={styles.checkoutBtn} onClick={submitCheckout} disabled={creating || items.length === 0}>
              {creating ? "Generating…" : "Checkout"}
            </button>
          </div>
        </div>
      )}
    </main>
  );
}
