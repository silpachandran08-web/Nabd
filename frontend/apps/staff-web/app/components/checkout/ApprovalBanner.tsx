"use client";

import { useState } from "react";
import { formatMoney } from "@nabd/domain";
import styles from "../../(clinic)/checkout/checkout.module.css";
import { api, reasonText } from "../../lib/api";
import { usePolling } from "../../lib/usePolling";
import { fromMinor, type DiscountApproval } from "./discountApproval";

/** Polls the request while it waits, only when the SSE stream is not up (usePolling stands down once it is). */
function WhileWaiting({ load }: { load: () => unknown }) {
  usePolling(load, 2000);
  return null;
}

/**
 * FIN-018: the bill's discounts are above the owner's threshold. Waiting: the bill can't be issued; 'Enter owner PIN'
 * (billing:approve) lets the owner approve here, 'Pay later' (billing:create) lets the patient leave. Approved: the next
 * checkout issues it. Controls the role lacks are absent.
 */
export default function ApprovalBanner({ approval, currency, canPin, canPayLater, onChange, onPayLater, reload, reasonLabel }: {
  approval: DiscountApproval; currency: string; canPin: boolean; canPayLater: boolean;
  onChange: (a: DiscountApproval) => void; onPayLater: () => void; reload: () => unknown; reasonLabel: (code: string) => string;
}) {
  const [pinOpen, setPinOpen] = useState(false);
  const [pin, setPin] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const amount = formatMoney(fromMinor(approval.amountMinor), currency);
  const reason = reasonLabel(approval.reasonCode);

  async function approveByPin() {
    setBusy(true);
    setError(null);
    try {
      const { stepUpToken } = await api.post<{ stepUpToken: string }>("/auth/pin-assertion", { pin });
      onChange(await api.post<DiscountApproval>(`/billing/discount-approvals/${approval.approvalId}/decide`,
        { decision: "approve", method: "pin", pinAssertion: stepUpToken }));
      setPinOpen(false);
    } catch (e) {
      setError(reasonText(e, "Couldn't approve with that PIN."));
    } finally {
      setPin(""); // the PIN never outlives the attempt
      setBusy(false);
    }
  }

  async function payLater() {
    setBusy(true);
    setError(null);
    try {
      await api.post<DiscountApproval>(`/billing/discount-approvals/${approval.approvalId}/pay-later`);
      onPayLater();
    } catch (e) {
      setError(reasonText(e, "Couldn't mark the bill as pay later."));
    } finally {
      setBusy(false);
    }
  }

  if (approval.billStatus === "approved") {
    return (
      <div className={styles.banner} role="status" data-testid="approval-banner">
        Discount approved by {approval.approverName ?? "the owner"}{approval.method === "pin" ? " (PIN)" : ""}
        <span className={styles.bannerSub}>{amount} ({approval.percent}%) · {reason}. Check out to issue the bill.</span>
      </div>
    );
  }
  if (approval.billStatus === "rejected") {
    return (
      <div className={styles.errorState} role="alert" data-testid="approval-banner">
        The owner rejected the discount of {amount} ({approval.percent}%). Change the discount, then check out again.
      </div>
    );
  }
  return (
    <div className={styles.banner} role="status" data-testid="approval-banner">
      <WhileWaiting load={reload} />
      Awaiting owner approval{approval.payLater ? " · patient will pay later" : ""}
      <span className={styles.bannerSub}>
        Discounts of {amount} ({approval.percent}%) are above the owner&apos;s threshold · {reason}. The bill can&apos;t be issued until the owner approves.
      </span>
      {error && <div className={styles.errorState} role="alert">{error}</div>}
      <div className={styles.approvalActions}>
        {canPin && !pinOpen && <button type="button" className={styles.approvalBtn} onClick={() => setPinOpen(true)}>Enter owner PIN</button>}
        {canPin && pinOpen && (
          <>
            <input className={styles.pinInput} type="password" inputMode="numeric" autoComplete="off" aria-label="Owner PIN" maxLength={8}
              value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} />
            <button type="button" className={styles.approvalBtn} disabled={busy || pin.length < 4} onClick={() => void approveByPin()}>
              {busy ? "Checking…" : "Approve"}
            </button>
            <button type="button" className={styles.approvalBtn} disabled={busy} onClick={() => { setPinOpen(false); setPin(""); }}>Cancel</button>
          </>
        )}
        {canPayLater && !approval.payLater && <button type="button" className={styles.approvalBtn} disabled={busy} onClick={() => void payLater()}>Pay later</button>}
      </div>
    </div>
  );
}
