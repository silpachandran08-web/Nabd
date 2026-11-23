"use client";

// PHL-043: this visit's lab orders, STAT first: status, the printable order sheet and cancel (uncollected orders only, with a reason).
import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { formatNumber } from "@nabd/i18n";
import { api, intentKeys, reasonText } from "../../lib/api";
import { CANCEL_REASON_MAX, byPriority, cancelReasonOk, type Cancelled, type LabOrder } from "../../lib/labOrders";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { openPdf } from "./LabTestsParts";
import s from "./consultParts.module.css";
import c from "./labTests.module.css";

const money = (v: number) => formatNumber(Number(v), { min: 2, max: 2 });

/** `version` goes up when the parent placed an order: the list reloads. */
export default function LabOrderList({ queueEntryId, version }: { queueEntryId: string; version: number }) {
  const t = useTranslations("consult.labTests");
  const [orders, setOrders] = useState<LabOrder[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [cancelling, setCancelling] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const keys = useRef(intentKeys()); // one Idempotency-Key per cancel: a retry after a timeout reuses it

  const load = useCallback(async () => {
    setLoadError(false);
    try { setOrders((await api.get<{ items: LabOrder[] }>(`/lab/orders?queueEntryId=${encodeURIComponent(queueEntryId)}`)).items); } catch { setLoadError(true); }
  }, [queueEntryId]);
  useEffect(() => { void Promise.resolve().then(load); }, [load, version]);

  async function cancel(o: LabOrder) {
    if (busy || !cancelReasonOk(reason)) return;
    setBusy(true); setProblem(null);
    const body = { reason: reason.trim() };
    try {
      const r = await api.post<Cancelled>(`/lab/orders/${o.id}/cancel`, body, { idempotencyKey: keys.current.get(`cancel-${o.id}`, body) });
      keys.current.done(`cancel-${o.id}`);
      setCancelling(null); setReason("");
      setNotice(r.chargesAlreadyBilled > 0 ? t("cancelledBilled", { count: r.chargesAlreadyBilled }) : t("cancelled", { count: r.chargesRemoved }));
      void load();
    } catch (e) { setProblem(reasonText(e, t("cancelFailed"))); } finally { setBusy(false); }
  }

  async function sheet(o: LabOrder) {
    setProblem(null);
    if (!(await openPdf(() => api.get<Blob>(`/lab/orders/${o.id}/sheet`, { as: "blob" })))) setProblem(t("sheetFailed"));
  }

  return (
    <>
      <h4 className={`${s.cardTitle} ${s.mtM}`}>{t("ordersTitle")}</h4>
      {notice && <div className={`${s.notice} ${s.noticeOk}`} role="status"><Icon name="check" size={16} /><span>{notice}</span></div>}
      {problem && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{problem}</span></div>}
      {orders === null && !loadError && <Skeleton rows={2} label={t("loading")} />}
      {loadError && <ErrorState message={t("ordersFailed")} onRetry={() => void load()} />}
      {orders !== null && orders.length === 0 && <p className={s.muted}>{t("noOrders")}</p>}
      {orders !== null && orders.length > 0 && (
        <ul className={c.orderList}>
          {byPriority(orders).map((o) => (
            <li key={o.id} className={c.orderRow}>
              <div className={s.listRow}>
                <strong>{o.accessionHint}</strong>
                <StatusPill tone={o.priority === "stat" ? "danger" : o.priority === "urgent" ? "warn" : "neutral"} label={t(`priorities.${o.priority}`)} />
                <StatusPill tone={o.status === "cancelled" ? "neutral" : "info"} label={t.has(`status.${o.status}`) ? t(`status.${o.status}`) : o.status} />
                <span className={`${s.muted} ${c.num}`}>{o.destinationKind === "external" ? t("notCharged") : money(o.total)}</span>
              </div>
              <span>{o.items.map((i) => i.name).join(", ")}</span>
              {o.status !== "cancelled" && (
                <div className={s.row}>
                  <button type="button" className={`${s.btn} ${c.target}`} onClick={() => void sheet(o)}>{t("printSheet")}</button>
                  {o.cancellable && cancelling !== o.id && (
                    <button type="button" className={`${s.btn} ${c.target}`} onClick={() => { setCancelling(o.id); setReason(""); }}>{t("cancel")}</button>
                  )}
                </div>
              )}
              {cancelling === o.id && (
                <div className={c.field}>
                  <label htmlFor={`lab-cancel-${o.id}`}>{t("cancelReason")}</label>
                  <input id={`lab-cancel-${o.id}`} className={`${s.input} ${c.input} ${c.target}`} maxLength={CANCEL_REASON_MAX} value={reason} onChange={(e) => setReason(e.target.value)} />
                  <div className={s.row}>
                    <button type="button" className={`${s.btnPrimary} ${c.target}`} disabled={busy || !cancelReasonOk(reason)} onClick={() => void cancel(o)}>{t("cancelConfirm")}</button>
                    <button type="button" className={`${s.btn} ${c.target}`} onClick={() => setCancelling(null)}>{t("keep")}</button>
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
