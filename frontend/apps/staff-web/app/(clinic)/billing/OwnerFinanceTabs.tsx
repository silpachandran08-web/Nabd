"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { moneyFormatter } from "@nabd/i18n";
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
  const showApprovals = can("billing:approve");
  const showLeakage = can("reports:view");
  const [tab, setTab] = useState<"approvals" | "leakage">(showApprovals ? "approvals" : "leakage");
  if (!showApprovals && !showLeakage) return null;
  return (
    <section className={styles.desk} aria-label="Discount approvals and leakage">
      <div className={styles.headerActions} role="tablist" aria-label="Owner finance">
        {showApprovals && <button type="button" role="tab" aria-selected={tab === "approvals"} className={tab === "approvals" ? styles.btnTap : styles.btnTapAlt}
          onClick={() => setTab("approvals")}>Approvals</button>}
        {showLeakage && <button type="button" role="tab" aria-selected={tab === "leakage"} className={tab === "leakage" ? styles.btnTap : styles.btnTapAlt}
          onClick={() => setTab("leakage")}>Leakage</button>}
      </div>
      {tab === "approvals" && showApprovals && <Approvals />}
      {tab === "leakage" && showLeakage && <Leakage today={today} />}
    </section>
  );
}

function Approvals() {
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
      setError(reasonText(e, "Couldn't record your decision."));
    } finally {
      setBusy(null);
      void q.refetch();
    }
  }

  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>Discounts waiting for you</div>
      {error && <div className={styles.error} role="alert">{error}</div>}
      {q.isPending ? <div className={styles.line}><span className={styles.state}>Loading…</span></div>
        : q.isError ? <div className={styles.line}><span className={styles.state} role="alert">Couldn&apos;t load the requests.</span>
          <button type="button" className={styles.btnTapAlt} onClick={() => void q.refetch()}>Retry</button></div>
        : q.data.items.length === 0 ? <div className={styles.line}><span className={styles.state}>Nothing is waiting for your approval.</span></div>
        : q.data.items.map((a) => (
          <div key={a.approvalId} className={styles.line}>
            <div className={styles.rowMain}>
              <div className={styles.rowName}>{moneyFormatter(a.currency).format(fromMinor(a.amountMinor))} · {a.percent}% of the bill</div>
              <div className={styles.rowSub}>{reasons.label(a.reasonCode)} · asked by {a.requestedByName} · {time(a.createdAt)}{a.payLater ? " · patient will pay later" : ""}</div>
            </div>
            <button type="button" className={styles.btnTap} disabled={busy !== null} onClick={() => void decide(a, "approve")}>Approve</button>
            <button type="button" className={styles.btnTapAlt} disabled={busy !== null} onClick={() => void decide(a, "reject")}>Reject</button>
          </div>
        ))}
      {can("setup:edit") && <Thresholds />}
    </div>
  );
}

function Thresholds() {
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
    if (av === null || pv === null) { setMsg("Enter an amount with up to 2 decimals and a percent from 0 to 100, or leave a box empty."); return; }
    setMsg(null);
    try {
      await api.patch("/setup/policies/discount_threshold_amount", { value: av });
      await api.patch("/setup/policies/discount_threshold_percent", { value: pv });
      setMsg("Saved.");
      void q.refetch();
    } catch (e) {
      setMsg(reasonText(e, "Couldn't save the threshold."));
    }
  }

  if (q.isError) return <div className={styles.countBox}><span className={styles.state}>Couldn&apos;t load the threshold.</span></div>;
  return (
    <div className={styles.countBox}>
      <span className={styles.countLabel}>Discounts above this need your approval (empty = no limit)</span>
      <div className={styles.countRow}>
        <input className={styles.inputTall} inputMode="decimal" aria-label="Threshold amount" placeholder="Amount" value={a}
          onChange={(e) => setAmount(e.target.value)} disabled={q.isPending} />
        <input className={styles.inputTall} inputMode="decimal" aria-label="Threshold percent of the bill" placeholder="% of bill" value={p}
          onChange={(e) => setPercent(e.target.value)} disabled={q.isPending} />
        <button type="button" className={styles.btnTap} onClick={() => void save()} disabled={q.isPending}>Save</button>
      </div>
      {msg && <div className={styles.rowSub} role="status">{msg}</div>}
    </div>
  );
}

function Leakage({ today }: { today: string }) {
  const reasons = usePriceReasons();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const q = useQuery({ queryKey: key("leakage", { from, to }), queryFn: getter<LeakageResponse>(`/billing/leakage?from=${from}&to=${to}`), enabled: !!from && !!to && from <= to });
  const money = q.data ? moneyFormatter(q.data.currency) : null;
  const t = q.data?.totals;
  return (
    <div className={styles.card}>
      <div className={styles.cardHead}>
        Leakage: discounts, below-list prices, write-offs and refunds
        <span className={styles.countRow}>
          <input type="date" className={styles.inputTall} aria-label="From" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          <input type="date" className={styles.inputTall} aria-label="To" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </span>
      </div>
      {q.isPending ? <div className={styles.line}><span className={styles.state}>Loading…</span></div>
        : q.isError ? <div className={styles.line}><span className={styles.state} role="alert">{reasonText(q.error, "Couldn't load the leakage report.")}</span>
          <button type="button" className={styles.btnTapAlt} onClick={() => void q.refetch()}>Retry</button></div>
        : (
          <>
            {t && money && (
              <div className={styles.line}>
                <span>Total <b className={styles.amt}>{money.format(fromMinor(t.totalMinor))}</b></span>
                <span className={styles.lineSub}>Discounts {money.format(fromMinor(t.discountMinor))} · Below list {money.format(fromMinor(t.overrideMinor))} · Write-offs {money.format(fromMinor(t.writeOffMinor))} · Refunds {money.format(fromMinor(t.refundMinor))} · Without your approval {money.format(fromMinor(t.withoutOwnerApprovalMinor))}</span>
              </div>
            )}
            {q.data.items.length === 0 ? <div className={styles.line}><span className={styles.state}>No discounts, write-offs or refunds in this period.</span></div>
              : q.data.items.map((i) => (
                <div key={`${i.type}:${i.id}`} className={styles.line}>
                  <div className={styles.rowMain}>
                    <div className={styles.rowName}>{LEAKAGE_TYPE_LABEL[i.type] ?? i.type} · {i.invoiceNumber ?? ""}</div>
                    <div className={styles.rowSub}>
                      {reasons.label(i.reasonCode)} · by {i.appliedByName ?? "—"} · approved by {i.approverName ?? "—"} ({APPROVAL_METHOD_LABEL[i.approvalMethod] ?? i.approvalMethod}) · {time(i.at)}
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
