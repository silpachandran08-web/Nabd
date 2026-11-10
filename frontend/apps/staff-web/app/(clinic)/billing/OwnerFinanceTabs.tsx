"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { moneyFormatter } from "@nabd/i18n";
import { useTranslations } from "next-intl";
import styles from "./billing.module.css";
import { api, reasonText } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { can } from "../../lib/permissions";
import { useLiveRefresh } from "../../lib/live";
import { usePriceReasons } from "../../components/checkout/priceReasons";
import {
  APPROVAL_METHOD_LABEL, LEAKAGE_TYPE_LABEL, fromMinor, thresholdInput, thresholdValue, type DiscountApproval,
} from "../../components/checkout/discountApproval";
import type { LeakageResponse } from "@nabd/api-client/generated";

type Policy = { policyKey: string; value: string };
const time = (instant: string) => new Date(instant).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

/** FIN-018 owner.finance: the discount approvals inbox (owner + billing:approve) and the leakage tab (owner + reports:view). */
export default function OwnerFinanceTabs({ today }: { today: string }) {
  const t = useTranslations("billing.ownerFinance");
  const showApprovals = can("billing:approve");
  const showLeakage = can("reports:view");
  const [tab, setTab] = useState<"approvals" | "leakage">(showApprovals ? "approvals" : "leakage");
  if (!showApprovals && !showLeakage) return null;
  return (
    <section className={styles.desk} aria-label={t("region")}>
      <div className={styles.headerActions} role="tablist" aria-label={t("tablist")}>
        {showApprovals && <button type="button" role="tab" aria-selected={tab === "approvals"} className={tab === "approvals" ? styles.btnTap : styles.btnTapAlt}
          onClick={() => setTab("approvals")}>{t("approvals")}</button>}
        {showLeakage && <button type="button" role="tab" aria-selected={tab === "leakage"} className={tab === "leakage" ? styles.btnTap : styles.btnTapAlt}
          onClick={() => setTab("leakage")}>{t("leakage")}</button>}
      </div>
      {tab === "approvals" && showApprovals && <Approvals />}
      {tab === "leakage" && showLeakage && <Leakage today={today} />}
    </section>
  );
}

function Approvals() {
  const t = useTranslations("billing.ownerFinance");
  const reasons = usePriceReasons();
  const q = useQuery({ queryKey: key("discount-approvals", { status: "pending" }), queryFn: getter<{ items: DiscountApproval[] }>("/billing/discount-approvals?status=pending") });
  useLiveRefresh(["discount-approvals"], () => q.refetch()); // a new request or a desk PIN decision shows at once
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function decide(a: DiscountApproval, decision: "approve" | "reject") {
    setBusy(a.approvalId);
    setError(null);
    try {
      await api.post(`/billing/discount-approvals/${a.approvalId}/decide`, { decision, method: "in_app" });
    } catch (e) {
      setError(reasonText(e, t("decideFailed")));
    } finally {
      setBusy(null);
      void q.refetch();
    }
  }

  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>{t("waiting")}</div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      {q.isPending ? <div className={styles.line}><span className={styles.state}>{t("loading")}</span></div>
        : q.isError ? <div className={styles.line}><span className={styles.state} role="alert">{t("loadFailed")}</span>
          <button type="button" className={styles.btnTapAlt} onClick={() => void q.refetch()}>{t("retry")}</button></div>
        : q.data.items.length === 0 ? <div className={styles.line}><span className={styles.state}>{t("nothingWaiting")}</span></div>
        : q.data.items.map((a) => (
          <div key={a.approvalId} className={styles.line}>
            <div className={styles.rowMain}>
              <div className={styles.rowName}>{t("amountOfBill", { amount: moneyFormatter(a.currency).format(fromMinor(a.amountMinor)), percent: a.percent })}</div>
              <div className={styles.rowSub}>{t("askedBy", { reason: reasons.label(a.reasonCode), name: a.requestedByName, time: time(a.createdAt) })}{a.payLater ? t("payLater") : ""}</div>
            </div>
            <button type="button" className={styles.btnTap} disabled={busy !== null} onClick={() => void decide(a, "approve")}>{t("approve")}</button>
            <button type="button" className={styles.btnTapAlt} disabled={busy !== null} onClick={() => void decide(a, "reject")}>{t("reject")}</button>
          </div>
        ))}
      {can("setup:edit") && <Thresholds />}
    </div>
  );
}

function Thresholds() {
  const t = useTranslations("billing.ownerFinance");
  const q = useQuery({ queryKey: key("setup-policies"), queryFn: getter<Policy[]>("/setup/policies") });
  const stored = (k: string) => q.data?.find((p) => p.policyKey === k)?.value;
  const [amount, setAmount] = useState<string | null>(null); // null = not edited yet: show the stored value
  const [percent, setPercent] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const a = amount ?? thresholdInput("amount", stored("discount_threshold_amount"));
  const p = percent ?? thresholdInput("percent", stored("discount_threshold_percent"));

  async function save() {
    const av = thresholdValue("amount", a);
    const pv = thresholdValue("percent", p);
    if (av === null || pv === null) { setMsg(t("thresholdInvalid")); return; }
    setMsg(null);
    try {
      await api.patch("/setup/policies/discount_threshold_amount", { value: av });
      await api.patch("/setup/policies/discount_threshold_percent", { value: pv });
      setMsg(t("saved"));
      void q.refetch();
    } catch (e) {
      setMsg(reasonText(e, t("thresholdSaveFailed")));
    }
  }

  if (q.isError) return <div className={styles.countBox}><span className={styles.state}>{t("thresholdLoadFailed")}</span></div>;
  return (
    <div className={styles.countBox}>
      <span className={styles.countLabel}>{t("thresholdLabel")}</span>
      <div className={styles.countRow}>
        <input className={styles.inputTall} inputMode="decimal" aria-label={t("thresholdAmount")} placeholder={t("amount")} value={a}
          onChange={(e) => setAmount(e.target.value)} disabled={q.isPending} />
        <input className={styles.inputTall} inputMode="decimal" aria-label={t("thresholdPercent")} placeholder={t("percentOfBill")} value={p}
          onChange={(e) => setPercent(e.target.value)} disabled={q.isPending} />
        <button type="button" className={styles.btnTap} onClick={() => void save()} disabled={q.isPending}>{t("save")}</button>
      </div>
      {msg && <div className={styles.rowSub} role="status">{msg}</div>}
    </div>
  );
}

function Leakage({ today }: { today: string }) {
  const t = useTranslations("billing.ownerFinance");
  const reasons = usePriceReasons();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const q = useQuery({ queryKey: key("leakage", { from, to }), queryFn: getter<LeakageResponse>(`/billing/leakage?from=${from}&to=${to}`), enabled: !!from && !!to && from <= to });
  const money = q.data ? moneyFormatter(q.data.currency) : null;
  const tot = q.data?.totals;
  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>
        {t("leakageTitle")}
        <span className={styles.countRow}>
          <input type="date" className={styles.inputTall} aria-label={t("from")} value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          <input type="date" className={styles.inputTall} aria-label={t("to")} value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </span>
      </div>
      {q.isPending ? <div className={styles.line}><span className={styles.state}>{t("loading")}</span></div>
        : q.isError ? <div className={styles.line}><span className={styles.state} role="alert">{reasonText(q.error, t("leakageFailed"))}</span>
          <button type="button" className={styles.btnTapAlt} onClick={() => void q.refetch()}>{t("retry")}</button></div>
        : (
          <>
            {tot && money && (
              <div className={styles.line}>
                <span>{t("total")} <b className={styles.amt}>{money.format(fromMinor(tot.totalMinor))}</b></span>
                <span className={styles.lineSub}>{t("breakdown", { discounts: money.format(fromMinor(tot.discountMinor)), override: money.format(fromMinor(tot.overrideMinor)), writeOffs: money.format(fromMinor(tot.writeOffMinor)), refunds: money.format(fromMinor(tot.refundMinor)), withoutApproval: money.format(fromMinor(tot.withoutOwnerApprovalMinor)) })}</span>
              </div>
            )}
            {q.data.items.length === 0 ? <div className={styles.line}><span className={styles.state}>{t("noLeakage")}</span></div>
              : q.data.items.map((i) => (
                <div key={`${i.type}:${i.id}`} className={styles.line}>
                  <div className={styles.rowMain}>
                    <div className={styles.rowName}>{t.has(`type.${i.type}`) ? t(`type.${i.type}`) : LEAKAGE_TYPE_LABEL[i.type] ?? i.type} · {i.invoiceNumber ?? ""}</div>
                    <div className={styles.rowSub}>
                      {t("leakageBy", { reason: reasons.label(i.reasonCode), by: i.appliedByName ?? "—", approver: i.approverName ?? "—", method: t.has(`method.${i.approvalMethod}`) ? t(`method.${i.approvalMethod}`) : APPROVAL_METHOD_LABEL[i.approvalMethod] ?? i.approvalMethod, time: time(i.at) })}
                    </div>
                  </div>
                  <span className={styles.amtBad}>{money?.format(fromMinor(i.amountMinor))}</span>
                </div>
              ))}
          </>
        )}
    </div>
  );
}
