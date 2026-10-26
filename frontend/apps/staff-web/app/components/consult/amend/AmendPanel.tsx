"use client";

import type { Amendments } from "./useAmendments";
import styles from "../consult.module.css";

/** "This note is finalised" banner with the Amend button (CLN-006: only the visit doctor or an approver sees it). */
export function SignedBanner({ canAmend, open, onToggle }: { canAmend: boolean; open: boolean; onToggle: () => void }) {
  return (
    <div className={styles.signedBanner}>
      <span>This note is finalised. Changes need a documented amendment with a reason, which is audited.</span>
      {canAmend && (
        <span className={styles.followUpControls}>
          <button className={styles.backBtn} onClick={onToggle}>{open ? "Cancel amendment" : "Amend note"}</button>
        </span>
      )}
    </div>
  );
}

export function AmendForm({ a }: { a: Amendments }) {
  return (
    <div className={`${styles.card} ${styles.cardPad16}`}>
      <div className={styles.noteField}>
        <label className={styles.noteLabel} htmlFor="amendReason">Reason for amendment</label>
        <input id="amendReason" className={styles.input} value={a.amendReason} maxLength={500} aria-describedby="amendReasonHint" onChange={(e) => a.setAmendReason(e.target.value)} />
        <span id="amendReasonHint" className={styles.saveStatus}>Required, 5 to 500 characters.</span>
      </div>
      <div className={`${styles.noteField} ${styles.mtS}`}>
        <label className={styles.noteLabel} htmlFor="amendDiagnosis">Corrected diagnosis (optional)</label>
        <input id="amendDiagnosis" className={styles.input} value={a.amendDiagnosis} onChange={(e) => a.setAmendDiagnosis(e.target.value)} />
      </div>
      <button className={`${styles.completeBtn} ${styles.mtS}`} onClick={a.submitAmendment} disabled={a.amending || a.amendReason.trim().length < 5}>
        {a.amending ? "Saving…" : "Save amendment"}
      </button>
      {a.amendError && <div className={styles.errorState} role="alert">{a.amendError}</div>}
    </div>
  );
}

export function AmendHistory({ a }: { a: Amendments }) {
  if (a.amendments.length === 0) return null;
  return (
    <div className={`${styles.card} ${styles.cardPad16}`}>
      <div className={`${styles.rxTitle} ${styles.rxTitleSm}`}>Amendment history</div>
      {a.amendments.map((x) => (
        <div key={x.id} className={styles.previousMedsRow}>
          <span className={styles.saveStatus}>{new Date(x.createdAt).toLocaleString()} — </span>
          {x.reason}{x.diagnosis ? ` (diagnosis: ${x.diagnosis})` : ""}
        </div>
      ))}
    </div>
  );
}
