"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { formatMoney } from "@nabd/domain";
import { useTranslations } from "next-intl";
import { usePaymentMethodLabel } from "../../lib/paymentMethodText";
import type { PharmacyReturnResponse, RefundRequestPage } from "@nabd/api-client/generated";
import Dialog from "../ui/Dialog";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { api, ApiError, errorText, intentKeys } from "../../lib/api";
import { can } from "../../lib/permissions";
import { getIdentity } from "../../lib/session";
import styles from "./pharmacyReturn.module.css";
import tableStyles from "../../(clinic)/checkout/checkout.module.css";
import { decisionErrorMessage, lineBlock, pendingReturns, returnBody, returnErrorMessage, type Pick, type RefundRequestResponse,
  type ReturnableBill } from "./pharmacyReturn";

// PHL-011 overlay:phreturn. Find the bill, tick sealed lines, send the return. It becomes a FIN-013 refund request: a second person
// (billing:approve) approves it below, which issues the credit note, refunds the original payment and puts the units back in their batch.
// The caller renders this only for billing:edit (the grant of POST /pharmacy/returns and GET /pharmacy/returns/bill).
export default function ReturnDialog({ initialNumber, onClose }: { initialNumber?: string; onClose: () => void }) {
  const t = useTranslations("checkout.returns");
  const paymentMethodLabel = usePaymentMethodLabel();
  const region = getIdentity()?.tenantRegion;
  const canDecide = can("billing:approve") && can("billing:view"); // GET /billing/refund-requests + approve / reject
  const [number, setNumber] = useState(initialNumber ?? "");
  const [bill, setBill] = useState<ReturnableBill | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [picks, setPicks] = useState<Record<string, Pick>>({});
  const [method, setMethod] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [sent, setSent] = useState<PharmacyReturnResponse | null>(null);
  const keys = useRef(intentKeys());

  const find = useCallback(async (n: string) => {
    if (!n.trim()) return;
    setLoading(true);
    setLoadError(null);
    setSent(null);
    setSendError(null);
    try {
      setBill(await api.get<ReturnableBill>(`/pharmacy/returns/bill?number=${encodeURIComponent(n.trim())}`));
      setPicks({});
      setMethod("");
    } catch (e) {
      setBill(null);
      setLoadError(e instanceof ApiError && e.status === 404 ? t("noBill") : errorText(e, t("loadFailed")));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (initialNumber) void Promise.resolve().then(() => find(initialNumber));
  }, [initialNumber, find]);

  function toggle(lineId: string, on: boolean) {
    setPicks((p) => {
      const next = { ...p };
      if (on) next[lineId] = { qty: "1", opened: false };
      else delete next[lineId];
      return next;
    });
  }

  async function send() {
    if (!bill) return;
    setSendError(null);
    const r = returnBody(bill, picks, method);
    if ("problem" in r) {
      setSendError(r.problem);
      return;
    }
    setSending(true);
    try {
      setSent(await api.post<PharmacyReturnResponse>("/pharmacy/returns", r.body, { idempotencyKey: keys.current.get("return", r.body) }));
      keys.current.done("return");
    } catch (e) {
      setSendError(e instanceof ApiError ? returnErrorMessage(e.problem, t("sendFailed")) : errorText(e, ""));
    } finally {
      setSending(false);
    }
  }

  return (
    <Dialog title={t("title")} onClose={onClose} size="lg">
      <form className={styles.find} onSubmit={(e) => { e.preventDefault(); void find(number); }}>
        <input className={styles.input} aria-label={t("billNumber")} placeholder={t("billPlaceholder")} value={number}
          onChange={(e) => setNumber(e.target.value)} />
        <button type="submit" className={styles.btn} disabled={loading || !number.trim()}>{t("findBill")}</button>
      </form>

      {loading && <Skeleton rows={3} label={t("loadingBill")} />}
      {!loading && loadError && <ErrorState message={loadError} onRetry={() => void find(number)} />}
      {!loading && !loadError && !bill && <EmptyState title={t("findTitle")} hint={t("findHint")} icon="receipt" />}

      {!loading && bill && (sent ? (
        <div className={styles.notice} role="status">
          {t("sentNotice", { amount: formatMoney(sent.amount, sent.currency), methods: Array.from(new Set(bill.refundMethods)).map((m) => paymentMethodLabel(m, region)).join(" / ") })}
        </div>
      ) : (
        <>
          <p className={tableStyles.muted}>{t("billStatus", { number: bill.invoiceNumber, status: bill.status })}</p>
          {bill.pendingRefundId && <div className={tableStyles.errorState} role="status">{t("pending")}</div>}
          {bill.lines.length === 0 ? <EmptyState title={t("noLines")} /> : (
            <table className={tableStyles.table}>
              <thead><tr><th scope="col">{t("colReturn")}</th><th scope="col">{t("colItem")}</th><th scope="col">{t("colSold")}</th><th scope="col">{t("colQty")}</th><th scope="col">{t("colOpened")}</th></tr></thead>
              <tbody>
                {bill.lines.map((l) => {
                  const p = picks[l.invoiceLineId];
                  const block = lineBlock(l, p?.opened ?? false);
                  return (
                    <tr key={l.invoiceLineId}>
                      <td>
                        <label className={styles.check}>
                          <input type="checkbox" aria-label={t("returnItem", { name: l.name })} checked={!!p} disabled={!l.allowed || !!bill.pendingRefundId}
                            onChange={(e) => toggle(l.invoiceLineId, e.target.checked)} />
                        </label>
                      </td>
                      <td>
                        {l.name}<div className={tableStyles.muted}>{t("priceTax", { price: formatMoney(l.unitPrice, bill.currency), rate: l.taxRatePercent })}</div>
                        {block && <div className={styles.reason}>{block}</div>}
                      </td>
                      <td>{l.soldQty}{l.returnedQty > 0 && <div className={tableStyles.muted}>{t("returned", { count: l.returnedQty })}</div>}</td>
                      <td>
                        {p && <input className={styles.qty} type="number" min={1} max={l.returnableQty} aria-label={t("qtyOf", { name: l.name })} value={p.qty}
                          onChange={(e) => setPicks((x) => ({ ...x, [l.invoiceLineId]: { ...p, qty: e.target.value } }))} />}
                      </td>
                      <td>
                        {p && (
                          <label className={styles.check}>
                            <input type="checkbox" aria-label={t("packOpened", { name: l.name })} checked={p.opened}
                              onChange={(e) => setPicks((x) => ({ ...x, [l.invoiceLineId]: { ...p, opened: e.target.checked } }))} />
                          </label>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
          {sendError && <div className={tableStyles.errorState} role="alert">{sendError}</div>}
          {!bill.pendingRefundId && bill.lines.some((l) => l.allowed) && (
            <div className={styles.actions}>
              {bill.refundMethods.length > 1 && (
                <select className={styles.input} aria-label={t("refundTo")} value={method} onChange={(e) => setMethod(e.target.value)}>
                  <option value="">{t("refundAsPaid")}</option>
                  {bill.refundMethods.map((m) => <option key={m} value={m}>{paymentMethodLabel(m, region)}</option>)}
                </select>
              )}
              {bill.refundMethods.length === 1 && <span className={tableStyles.muted}>{t("refundToMethod", { method: paymentMethodLabel(bill.refundMethods[0], region) })}</span>}
              <button type="button" className={styles.primary} onClick={() => void send()} disabled={sending || Object.keys(picks).length === 0}>
                {sending ? t("sending") : t("send")}
              </button>
            </div>
          )}
        </>
      ))}

      {canDecide && <PendingReturns reloadKey={sent?.refundId ?? ""} />}
    </Dialog>
  );
}

/** Counter returns waiting for a second person (billing:approve). Absent for everyone else. */
function PendingReturns({ reloadKey }: { reloadKey: string }) {
  const t = useTranslations("checkout.returns");
  const [items, setItems] = useState<RefundRequestResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actError, setActError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setItems(pendingReturns((await api.get<RefundRequestPage>("/billing/refund-requests?status=pending")).items ?? []));
    } catch (e) {
      setError(errorText(e, t("pendingFailed")));
    }
  }, [t]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load, reloadKey]);

  async function decide(r: RefundRequestResponse, approve: boolean) {
    setBusy(r.id ?? null);
    setActError(null);
    setNotice(null);
    try {
      if (approve) {
        const ok = await api.post<{ creditNoteNumber: string }>(`/billing/refund-requests/${r.id}/approve`, { expectedAmount: r.amount });
        setNotice(t("creditIssued", { number: ok.creditNoteNumber }));
      } else {
        await api.post(`/billing/refund-requests/${r.id}/reject`, { reason: "Return refused at the counter" });
        setNotice(t("rejected", { number: r.invoiceNumber ?? "" }));
      }
      await load();
    } catch (e) {
      setActError(e instanceof ApiError ? decisionErrorMessage(e.problem, t("decideFailed")) : errorText(e, ""));
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className={styles.section} aria-label={t("waiting")}>
      <h3 className={tableStyles.cardTitle}>{t("waiting")}</h3>
      {notice && <div className={styles.notice} role="status">{notice}</div>}
      {actError && <div className={tableStyles.errorState} role="alert">{actError}</div>}
      {error ? <ErrorState message={error} onRetry={() => void load()} />
        : items === null ? <Skeleton rows={2} label={t("loadingReturns")} />
        : items.length === 0 ? <EmptyState title={t("noneWaiting")} icon="check" />
        : items.map((r) => (
          <div key={r.id} className={styles.pending}>
            <span>{r.invoiceNumber} · {formatMoney(r.amount ?? 0, r.currency ?? "")}<span className={tableStyles.muted}>{t("by", { name: r.requestedByName ?? t("unknown") })}</span></span>
            <span className={styles.rowActions}>
              <button type="button" className={styles.primary} disabled={busy !== null} onClick={() => void decide(r, true)}>{t("approve")}</button>
              <button type="button" className={styles.btn} disabled={busy !== null} onClick={() => void decide(r, false)}>{t("reject")}</button>
            </span>
          </div>
        ))}
    </section>
  );
}
