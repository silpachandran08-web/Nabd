"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import styles from "../checkout.module.css";
import { formatMoney, taxRegime } from "@nabd/domain";
import { useTranslations } from "next-intl";
import { usePaymentMethodLabel } from "../../../lib/paymentMethodText";
import BillingStatePill from "../../../components/ui/BillingStatePill";
import StockPill, { stockErrorMessage } from "../../../components/ui/StockPill";
import StatusPill from "../../../components/ui/StatusPill";
import { autoLink, rxErrorMessage, unlinkedRx } from "../../../components/checkout/rxLink";
import { getIdentity } from "../../../lib/session";
import { can } from "../../../lib/permissions";
import InvoiceActions from "../../../components/checkout/InvoiceActions";
import TenderRows from "../../../components/checkout/TenderRows";
import { filled, tenderError, tenderTotalMinor, toMinor, type Tender } from "../../../components/checkout/tender";
import { api, ApiError, errorText, intentKeys } from "../../../lib/api";
import type { Charge, CheckoutContext, FlowStep, Invoice, LineItem } from "@nabd/api-client/types";
import { usePriceReasons } from "../../../components/checkout/priceReasons";
import ApprovalBanner from "../../../components/checkout/ApprovalBanner";
import { bannerApproval, needsApproval, type DiscountApproval } from "../../../components/checkout/discountApproval";
import { useLiveRefresh } from "../../../lib/live";
import { FieldError, useFormErrors } from "../../../lib/formErrors";
import { useFormValidation, useUnsavedGuard } from "../../../lib/formValidation";
import { discountSchema } from "../../../lib/formSchemas";
import TierControl from "../../../components/checkout/TierControl";
import ChargeButton from "../../../components/ui/ChargeButton";
import { tierPrice, tierToSend, type TierLine, type TierName } from "../../../lib/pricingTier";
import RxPrefillBlock from "../../../components/checkout/RxPrefillBlock";
import { initialTicks, toBillLine, type PrefillLine } from "../../../components/checkout/rxPrefill";


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
  const tr = useTranslations("checkout.visit");
  const methodLabel = usePaymentMethodLabel();
  const router = useRouter();
  const region = getIdentity()?.tenantRegion;
  // UI gating only (absent, not disabled) — the API enforces the same grants.
  const canBill = can("billing:create");   // POST /billing/checkout/{id}
  const canPay = can("billing:edit");      // POST /billing/invoices/{id}/payments
  const canAdvance = can("queue:edit");    // PATCH /queue/{id}/status
  const canDiscount = can("billing.discount"); // REC-043: input absent, and discount not sent, without it
  const canApprove = can("billing:approve"); // FIN-018: 'Enter owner PIN' (POST /billing/discount-approvals/{id}/decide)
  const reasons = usePriceReasons();
  const reasonLabel = reasons.label;
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
  const fe = useFormErrors("checkout");
  const fv = useFormValidation(fe);
  const [editing, setEditing] = useState<string | null>(null); // chargeCode whose override editor is open
  const [ovPrice, setOvPrice] = useState("");
  const [ovReason, setOvReason] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [tierPicks, setTierPicks] = useState<Record<string, TierName>>({}); // FIN-016: chargeCode -> the receptionist's tier pick

  const [tenders, setTenders] = useState<Tender[]>([{ id: 1, method: "cash", amount: "" }]);
  const [recording, setRecording] = useState(false);
  const busyLock = useRef(false); // synchronous guard: a double-click must never start a second bill or payment
  const [paymentError, setPaymentError] = useState<string | null>(null);

  const [flowSteps, setFlowSteps] = useState<FlowStep[]>([]);
  const [continuing, setContinuing] = useState(false);
  const [continueError, setContinueError] = useState<string | null>(null);
  const [showAddMore, setShowAddMore] = useState(false);
  // PHL-018: the visit's in-house Rx lines, which are ticked into `items`, and the "dispense anyway" reasons
  const [prefill, setPrefill] = useState<PrefillLine[]>([]);
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const [rxReasons, setReasons] = useState<Record<string, string>>({});
  const [approval, setApproval] = useState<DiscountApproval | null>(null); // FIN-018: this bill's owner-approval request

  const keys = useRef(intentKeys()); // Idempotency-Key per user intent: reused on a retry of the same bill/payment/step

  // Best-effort: a failure leaves the plain charge picker. `tick` pre-fills the bill with the lines the shelf covers (first billing stop only).
  const loadPrefill = useCallback(async (ctx: CheckoutContext, tick: boolean) => {
    const lines = canBill ? (await api.get<{ lines: PrefillLine[] }>(`/billing/checkout/${queueEntryId}/prefill`).catch(() => ({ lines: [] as PrefillLine[] }))).lines : [];
    setPrefill(lines);
    setReasons({});
    const on = tick ? initialTicks(lines) : [];
    setTicked(new Set(on));
    const add = lines.filter((l) => on.includes(l.chargeCode)).flatMap((l) => {
      const c = ctx.charges.find((x) => x.code === l.chargeCode);
      return c ? [toBillLine(l, c)] : [];
    });
    if (add.length > 0) setItems((prev) => [...prev, ...add.filter((a) => !prev.some((i) => i.chargeCode === a.chargeCode))]);
  }, [queueEntryId, canBill]);

  // FIN-018: the newest approval request for this bill. On a fresh page it also puts the waiting cart back.
  const loadApproval = useCallback(async (ctx?: CheckoutContext) => {
    const res = await api.get<{ items: DiscountApproval[] }>(`/billing/discount-approvals?invoiceDraftId=${queueEntryId}`).catch(() => null);
    if (!res) return;
    const a = bannerApproval(res.items);
    setApproval(a);
    if (a && ctx && a.billStatus !== "rejected") {
      const lines = (a.cart.lineItems ?? []).flatMap((l): LineItem[] => {
        const c = ctx.charges.find((x) => x.code === l.chargeCode);
        if (!c && !l.chargeName) return [];
        const price = l.override?.unitPrice ?? c?.baseAmount ?? l.unitPrice ?? 0;
        return [{ chargeCode: l.chargeCode, chargeName: c?.name ?? l.chargeName ?? l.chargeCode, category: c?.category ?? l.category ?? "Service",
          quantity: l.quantity ?? 1, unitPrice: price, taxRatePercent: c?.taxRatePercent ?? l.taxRatePercent ?? 0,
          ...(l.override ? { override: { unitPrice: price, reasonCode: l.override.reasonCode ?? "" } } : {}),
          ...(l.prescriptionItemId ? { prescriptionItemId: l.prescriptionItemId } : {}) }];
      });
      setItems(lines);
      const d = (a.cart.discounts ?? []).find((x) => x.kind === "amount");
      setDiscount(d ? String(d.value) : "0");
      setDiscountReason(d?.reasonCode ?? "");
    }
  }, [queueEntryId]);
  useLiveRefresh(["discount-approvals"], () => loadApproval()); // SSE `billing` topic: the owner's decision lands here

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
      await loadPrefill(ctx, !inv);
      if (canBill) await loadApproval(ctx);
    } catch (e) {
      if (e instanceof ApiError && e.status === 403) setForbidden(true);
      else setError(e instanceof ApiError ? tr("loadFailed") : tr("unreachable"));
    } finally {
      setLoading(false);
    }
  }, [queueEntryId, loadPrefill, loadApproval, canBill, tr]);


  useUnsavedGuard(!invoice && (items.length > 0 || Number(discount) !== 0 || tenders.some((t) => t.amount !== ""))); // FEF-033
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  const tierLines = (context?.suggestedLines ?? []) as unknown as TierLine[]; // FIN-015/016 fields arrive with the next client regeneration
  const tierLineOf = (code: string) => tierLines.find((t) => t.chargeCode === code);

  function pickTier(code: string, tier: TierName) {
    const tl = tierLineOf(code);
    if (!tl) return;
    setTierPicks((prev) => ({ ...prev, [code]: tier }));
    setItems((prev) => prev.map((i) => (i.chargeCode === code && !i.override ? { ...i, unitPrice: tierPrice(tl, tier) } : i)));
  }

  function effectivePrice(charge: Charge): number {
    const suggested = charge.category === "Consultation" ? tierLineOf(charge.code) : undefined;
    if (suggested) return suggested.unitPrice; // the server's pick: doctor fee, follow-up or (urgent check-in) emergency
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

  function toggleRx(l: PrefillLine, on: boolean, reason?: string) {
    setTicked((prev) => { const n = new Set(prev); if (on) n.add(l.chargeCode); else n.delete(l.chargeCode); return n; });
    const c = context?.charges.find((x) => x.code === l.chargeCode);
    setItems((prev) => {
      const rest = prev.filter((i) => i.chargeCode !== l.chargeCode);
      return on && c ? [...rest, toBillLine(l, c, reason ?? rxReasons[l.chargeCode])] : rest;
    });
  }

  function setRxReason(l: PrefillLine, reason: string | null) {
    setReasons((prev) => { const n = { ...prev }; if (reason) n[l.chargeCode] = reason; else delete n[l.chargeCode]; return n; });
    toggleRx(l, reason != null, reason ?? undefined);
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
      setCreateError(tr("addOneCharge"));
      return null;
    }
    // FEF-033: a typed discount (even a negative one) is checked against the API's discount schema; errors sit on the fields.
    if (canDiscount && Number(discount) !== 0 && !fv.submit(discountSchema(subtotal), { value: Number(discount), reasonCode: discountReason }, document.body)) return null;
    setCreating(true);
    const body = {
        // codes only: the server prices every line from the catalogue (FIN-003)
        lineItems: items.map((i) => ({ chargeCode: i.chargeCode, quantity: i.quantity, ...(canDiscount && i.override ? { override: i.override } : {}),
          ...(() => { const tl = tierLineOf(i.chargeCode); const t = tl ? tierToSend(tl, tierPicks[i.chargeCode]) : undefined; return t ? { tier: t } : {}; })(),
          ...(i.prescriptionItemId ? { prescriptionItemId: i.prescriptionItemId } : {}), ...(i.shortfallReason ? { shortfallReason: i.shortfallReason } : {}) })),
        ...(canDiscount && discountNum > 0 ? { discounts: [{ kind: "amount", value: discountNum, reasonCode: discountReason }] } : {}),
        ...(approval?.billStatus === "approved" ? { discountApprovalId: approval.approvalId } : {}),
      };
    try {
      const inv = await api.post<Invoice>(`/billing/checkout/${queueEntryId}`, body, { idempotencyKey: keys.current.get("bill", body) });
      keys.current.done("bill");
      setInvoice(inv);
      setApproval(null);
      setItems([]); // picker resets — a later billing checkpoint starts its "add more" list fresh
      if (context) void loadPrefill(context, false);
      setDiscount("0");
      setDiscountReason("");
      setShowAddMore(false);
      return inv;
    } catch (e) {
      // FIN-018: above the owner's threshold the bill waits; ask the owner (push + in-app inbox) with the same cart.
      if (e instanceof ApiError && e.status === 409 && needsApproval(e.problem)) {
        await requestApproval(body);
        return null;
      }
      // A server answer is a refusal; no answer (or a timeout) leaves the outcome unknown.
      setCreateError(e instanceof ApiError ? rxErrorMessage(e.problem) ?? stockErrorMessage(e.problem, e.detail || tr("invoiceFailed")) : tr("invoiceUnknown"));
      return null;
    } finally {
      setCreating(false);
    }
  }

  async function requestApproval(body: { lineItems: unknown[]; discounts?: unknown[] }) {
    const req = { invoiceDraftId: queueEntryId, lineItems: body.lineItems, ...(body.discounts ? { discounts: body.discounts } : {}) };
    try {
      setApproval(await api.post<DiscountApproval>("/billing/discount-approvals", req, { idempotencyKey: keys.current.get("approval", req) }));
      keys.current.done("approval");
    } catch (e) {
      setCreateError(errorText(e, tr("approvalSendFailed")));
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
          setPaymentError(tr("overBalance"));
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
            setPaymentError(e.detail || tr("paymentFailed"));
            if (e.status === 422) { // FIN-001: overpayment names the balance; refresh the one shown
              const r = await api.get<Invoice>(`/billing/checkout/${queueEntryId}/invoice`).catch(() => null);
              if (r) setInvoice(r);
            }
            return;
          }
          // Outcome unknown: read the invoice back instead of guessing, so a payment that landed is never re-sent.
          const r = await api.get<Invoice>(`/billing/checkout/${queueEntryId}/invoice`).catch(() => null);
          if (!r) {
            setPaymentError(tr("paymentUnknown"));
            return;
          }
          next = r;
          if (next.payments.length <= before) {
            setInvoice(next);
            setPaymentError(tr("paymentNotThrough"));
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
        setContinueError(tr("noNextStep"));
        return;
      }
      await api.patch(`/queue/${queueEntryId}/status`, { status: next, expectedStatus: context.queueStatus }, { idempotencyKey: keys.current.get("advance", next) });
      router.push("/arrivals");
    } catch (e) {
      setContinueError(errorText(e, tr("continueFailed")));
    } finally {
      setContinuing(false);
    }
  }

  if (loading) return <main className={styles.page}><div className={styles.state}>{tr("loading")}</div></main>;
  if (forbidden) return <main className={styles.page}><div className={styles.state}>{tr("noAccess")}</div></main>;
  if (error || !context) return <main className={styles.page}><div className={styles.errorState}>{error ?? tr("somethingWrong")}</div></main>;

  const taxName = taxRegime(getIdentity()?.tenantRegion).label; // FEF-040: GST or VAT from the market, never a literal
  const categories = ["All", ...Array.from(new Set(context.charges.map((c) => c.category)))];
  const visibleCharges = category === "All" ? context.charges : context.charges.filter((c) => c.category === category);

  const picker = (
    <>
    <RxPrefillBlock lines={prefill} ticked={ticked} reasons={rxReasons} onToggle={(l, on) => toggleRx(l, on)} onReason={setRxReason} />
    <div className={styles.layout}>
      <div className={styles.card}>
        <h2 className={styles.cardTitle}>{tr("charges")}</h2>
        <div className={styles.categoryTabs}>
          {categories.map((c) => (
            <button key={c} className={category === c ? styles.catTabActive : styles.catTab} onClick={() => setCategory(c)}>{c === "All" ? tr("all") : c}</button>
          ))}
        </div>
        {visibleCharges.map((c) => {
          const price = effectivePrice(c);
          const isFollowUp = price !== c.baseAmount;
          const out = c.stockQty != null && c.stockQty <= 0;
          // PHA-011: a 0-stock item is flagged and not offered (no button at all).
          return (
            <ChargeButton key={c.id} dimmed={out} onClick={out ? undefined : () => addCharge(c)}
              label={<>{c.name} {c.isRx && <StatusPill tone="danger" label={tr("rx")} />} <StockPill qty={c.stockQty} /></>}
              price={<>
                {isFollowUp && <span className={styles.muted} style={{ textDecoration: "line-through", marginRight: 6 }}>{formatMoney(c.baseAmount, context.currency)}</span>}
                {formatMoney(price, context.currency)}
              </>} />
          );
        })}
      </div>

      <div className={styles.card}>
        <h2 className={styles.cardTitle}>{invoice ? tr("newCharges") : tr("selectedItems")}</h2>
        {items.length === 0 ? (
          <div className={styles.muted}>{tr("pickHint")}</div>
        ) : (
          <table className={styles.table}>
            <thead><tr><th scope="col">{tr("colCharge")}</th><th scope="col">{tr("colQty")}</th><th scope="col">{tr("colUnitPrice")}</th><th scope="col">{tr("colTax", { tax: taxName })}</th><th scope="col">{tr("colLineTotal")}</th><th scope="col"></th></tr></thead>
            <tbody>
              {items.map((i) => (
                <tr key={i.chargeCode}>
                  <td>
                    {i.chargeName}<div className={styles.muted}>{i.category}</div>
                    {/* PHL-001: an Rx line is linked to one signed line of this visit's prescription; no "unlink" choice once linked */}
                    {(i.prescriptionItemId || waitingForRx.includes(i.chargeCode)) && (
                      <select aria-label={tr("rxLineFor", { name: i.chargeName })} className={styles.rxPickerInput} value={i.prescriptionItemId ?? ""}
                        onChange={(e) => e.target.value && linkRx(i.chargeCode, e.target.value)}>
                        {!i.prescriptionItemId && <option value="">{rxChoices.length ? tr("chooseRxLine") : tr("noRxLine")}</option>}
                        {(context.prescribedItems ?? []).filter((p) => p.remainingQty > 0 || p.prescriptionItemId === i.prescriptionItemId).map((p) => (
                          <option key={p.prescriptionItemId} value={p.prescriptionItemId}>{tr("rxOption", { drug: p.drugName, dosage: p.dosage ? ` ${p.dosage}` : "", left: p.remainingQty })}</option>
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
                    {i.category === "Consultation" && tierLineOf(i.chargeCode) && (
                      <TierControl line={tierLineOf(i.chargeCode)!} picked={tierPicks[i.chargeCode] ?? tierLineOf(i.chargeCode)!.tier} currency={context.currency}
                        onPick={(t) => pickTier(i.chargeCode, t)} />
                    )}
                    {i.override && <div className={styles.muted}>{tr("override", { reason: reasonLabel(i.override.reasonCode) })}</div>}
                    {canDiscount && editing !== i.chargeCode && (
                      <button type="button" className={styles.removeBtn} onClick={() => { setEditing(i.chargeCode); setOvPrice(String(i.unitPrice)); setOvReason(i.override?.reasonCode ?? ""); }}>{tr("overridePrice")}</button>
                    )}
                    {canDiscount && editing === i.chargeCode && (
                      <div>
                        <input aria-label={tr("overrideUnitPrice")} className={styles.discountInput} type="number" step="0.01" min="0" value={ovPrice} onChange={(e) => setOvPrice(e.target.value)} />
                        <select aria-label={tr("overrideReason")} className={styles.discountInput} value={ovReason} onChange={(e) => setOvReason(e.target.value)}>
                          <option value="">{tr("reasonPlaceholder")}</option>
                          {reasons.items.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
                        </select>
                        <button type="button" className={styles.removeBtn} disabled={!ovReason || ovPrice === ""} onClick={() => applyOverride(i.chargeCode)}>{tr("apply")}</button>
                        <button type="button" className={styles.removeBtn} onClick={() => setEditing(null)}>{tr("cancel")}</button>
                      </div>
                    )}
                  </td>
                  <td>{i.taxRatePercent}%</td>
                  <td>{formatMoney(i.unitPrice * i.quantity, context.currency)}</td>
                  <td><button type="button" className={styles.removeBtn} onClick={() => setQty(i.chargeCode, 0)}>{tr("remove")}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className={styles.card}>
        <div className={styles.totals}>
          <div className={styles.totalsRow}><span>{tr("subtotal")}</span><span>{formatMoney(subtotal, context.currency)}</span></div>
          {canDiscount && (
            <div className={styles.totalsRow}>
              <span>{tr("discount")}</span>
              <input aria-label={tr("discountAmount")} className={styles.discountInput} type="number" step="0.01" min="0" value={discount} onChange={(e) => { fe.clearField("value"); setDiscount(e.target.value); }} onBlur={() => Number(discount) !== 0 && fv.blur(discountSchema(subtotal), "value", { value: Number(discount), reasonCode: "x" })} {...fe.attrs("value")} />
            </div>
          )}
          {canDiscount && <FieldError fe={fe} name="value" className={styles.errorState} />}
          {canDiscount && Number(discount) > 0 && (
            <div className={styles.totalsRow}>
              <span>{tr("discountReason")}</span>
              <select aria-label={tr("discountReason")} className={styles.discountInput} value={discountReason} onChange={(e) => { fe.clearField("reasonCode"); setDiscountReason(e.target.value); }} onBlur={() => fv.blur(discountSchema(subtotal), "reasonCode", { value: 1, reasonCode: discountReason })} {...fe.attrs("reasonCode")}>
                <option value="">{tr("reasonPlaceholder")}</option>
                {reasons.items.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
            </div>
          )}
          {canDiscount && <FieldError fe={fe} name="reasonCode" className={styles.errorState} />}
          <div className={styles.totalsRow}><span>{tr("taxRow", { tax: taxName })}</span><span>{formatMoney(tax, context.currency)}</span></div>
          <div className={styles.totalsRow}><span>{tr("roundOff")}</span><span>{roundOff >= 0 ? "+" : "−"}{formatMoney(Math.abs(roundOff), context.currency)}</span></div>
          <div className={styles.totalsRowMain}><span>{tr("total")}</span><span>{formatMoney(previewTotal, context.currency)}</span></div>
        </div>
        <p className={styles.muted}>{tr("totalNote")}</p>
        {waitingForRx.length > 0 && (
          <div className={styles.errorState} data-testid="rx-wait">{tr("rxWait")}</div>
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
    </>
  );
  const tenderedMinor = tenderTotalMinor(tenders);
  // Sticky Collect bar. Balance due = total minus what is typed into the split rows (never negative).
  const collectBar = (
    <div className={styles.collectBar}>
      <div className={styles.collectNums}>
        <span>{tr("total")} <b>{formatMoney(previewTotal, context.currency)}</b></span>
        {canPay && !invoice && <span>{tr("balanceDue")} <b data-testid="balance-due">{formatMoney(Math.max(0, previewTotal * 100 - tenderedMinor) / 100, context.currency)}</b></span>}
      </div>
      <button className={styles.collectBtn} onClick={invoice ? submitCheckout : collect} disabled={creating || recording || (!invoice && items.length === 0) || waitingForRx.length > 0}>
        {creating || recording ? tr("working") : invoice ? tr("addToInvoice") : canPay && tenderedMinor > 0 ? tr("collectAmount", { amount: formatMoney(tenderedMinor / 100, context.currency) }) : tr("checkout")}
      </button>
    </div>
  );

  return (
    <main className={styles.page}>
      <div className={styles.strip}>
        <div className={styles.stripIdentity}>
          <button className={styles.backBtn} onClick={() => router.push("/arrivals")}>{tr("backArrivals")}</button>
          <span className={styles.stripName}>{context.patientName}</span>
          <span className={styles.stripMeta}>{context.doctorName} · {context.visitType}</span>
        </div>
        {invoice && <BillingStatePill state={invoice.status} />}
      </div>

      {context.followUpEligible && !invoice && (
        <div className={styles.banner}>
          {tr("followUp")}
          <span className={styles.bannerSub}>{tr("followUpSub")}</span>
        </div>
      )}

      {context.prescribedItems.length > 0 && (
        <div className={styles.banner}>
          {tr("rxReady", { count: context.prescribedItems.length })}
          <span className={styles.bannerSub}>{tr("rxReadySub")}</span>
        </div>
      )}

      {invoice && (
        <div className={styles.layout} style={{ gridTemplateColumns: "1fr 300px" }}>
          <div className={styles.card}>
            <h2 className={styles.cardTitle}>{invoice.invoiceNumber}</h2>
            <table className={styles.table}>
              <thead><tr><th scope="col">{tr("colCharge")}</th><th scope="col">{tr("colQty")}</th><th scope="col">{tr("colUnitPrice")}</th><th scope="col">{tr("colTaxPct", { tax: taxName })}</th><th scope="col">{tr("colLineTotal")}</th></tr></thead>
              <tbody>
                {invoice.lineItems.map((li) => (
                  <tr key={li.chargeCode}>
                    <td>{li.chargeName}<div className={styles.muted}>{li.category}</div></td>
                    <td>{li.quantity}</td>
                    <td>
                      {formatMoney(li.unitPrice, invoice.currency)}
                      {li.overrideReason && li.listUnitPrice != null && (
                        <div className={styles.muted}>{tr("listPrice", { price: formatMoney(li.listUnitPrice, invoice.currency), reason: reasonLabel(li.overrideReason) })}</div>
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
                <h2 className={styles.cardTitle} style={{ marginTop: "16px" }}>{tr("payments")}</h2>
                {invoice.payments.map((p) => (
                  <div key={p.id} className={styles.paymentRow}>
                    <span>{methodLabel(p.method, region)}</span>
                    <span>{formatMoney(p.amount, invoice.currency)}</span>
                  </div>
                ))}
              </>
            )}
            {!showAddMore && canBill && invoice.status !== "void" && (
              <button type="button" className={styles.backBtn} style={{ marginTop: "16px" }} onClick={() => setShowAddMore(true)}>
                {tr("addMore")}
              </button>
            )}
          </div>

          <div className={styles.card}>
            <div className={styles.totals}>
              <div className={styles.totalsRow}><span>{tr("subtotal")}</span><span>{formatMoney(invoice.subtotal, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>{tr("discount")}</span><span>−{formatMoney(invoice.discount, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>{tr("taxRow", { tax: taxName })}</span><span>{formatMoney(invoice.tax, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>{tr("roundOff")}</span><span>{invoice.roundOff >= 0 ? "+" : "−"}{formatMoney(Math.abs(invoice.roundOff), invoice.currency)}</span></div>
              <div className={styles.totalsRowMain}><span>{tr("total")}</span><span>{formatMoney(invoice.total, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>{tr("paid")}</span><span>{formatMoney(invoice.paid, invoice.currency)}</span></div>
              <div className={styles.totalsRow}><span>{tr("balanceDue")}</span><span>{formatMoney(invoice.balanceDue, invoice.currency)}</span></div>
            </div>

            {invoice.id && invoice.status !== "void" && can("billing:view") && invoice.payments.length > 0 && <InvoiceActions invoiceId={invoice.id} />}

            {context.queueStatus === "billing_pending" && canAdvance && (
              <>
                <p className={styles.muted} style={{ marginTop: "12px" }}>
                  {tr("interim")}
                </p>
                {continueError && <div className={styles.errorState}>{continueError}</div>}
                <button className={styles.checkoutBtn} onClick={continueVisit} disabled={continuing}>
                  {continuing ? tr("continuing") : tr("continueVisit")}
                </button>
              </>
            )}

            {context.queueStatus === "checkout_pending" && canBill && invoice.status !== "void" && (
              <>
                <p className={styles.muted} style={{ marginTop: "12px" }}>
                  {invoice.balanceDue > 0 ? tr("remaining") : tr("nothingFurther")}
                </p>
                {createError && <div className={styles.errorState}>{createError}</div>}
                <button className={styles.checkoutBtn} onClick={submitCheckout} disabled={creating}>
                  {creating ? tr("completing") : tr("complete")}
                </button>
              </>
            )}

            {invoice.status !== "paid" && invoice.status !== "void" && canPay && (
              <>
                <TenderRows rows={tenders} onChange={setTenders} region={region} balanceMinor={Math.round(invoice.balanceDue * 100)} disabled={creating || recording} />
                {paymentError && <div className={styles.errorState}>{paymentError}</div>}
                <button className={styles.recordBtn} onClick={collectOnInvoice} disabled={creating || recording || tenderTotalMinor(tenders) === 0}>
                  {recording ? tr("recording") : tr("collectAmount", { amount: formatMoney(tenderTotalMinor(tenders) / 100, invoice.currency) })}
                </button>
              </>
            )}
          </div>
        </div>
      )}

      {approval && canBill && (
        <ApprovalBanner approval={approval} currency={context.currency} canPin={canApprove} canPayLater={canBill} reasonLabel={reasonLabel}
          onChange={setApproval} onPayLater={() => router.push("/arrivals")} reload={() => loadApproval()} />
      )}
      {canDiscount && reasons.error && <div className={styles.errorState} role="alert">{tr("reasonsFailed")}</div>}
      {!canBill && !invoice && <div className={styles.state}>{tr("noInvoice")}</div>}
      {canBill && (!invoice || showAddMore) && <>{picker}{collectBar}</>}
    </main>
  );
}
