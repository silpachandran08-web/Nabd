"use client";

import { useState } from "react";
import Dialog from "../../components/ui/Dialog";
import styles from "./patients.module.css";

// Confirmation for a destructive or safety-relevant change, built on the shared Dialog (never
// window.confirm/prompt). With `reasonLabel` a non-empty reason is required and passed to onConfirm.
// A failed or cancelled action stays visible: `error` is shown inside the dialog and it stays open.
export default function ConfirmDialog({
  title, message, confirmLabel, reasonLabel, busy, error, onConfirm, onClose,
}: {
  title: string; message: string; confirmLabel: string; reasonLabel?: string; busy: boolean; error: string | null;
  onConfirm: (reason: string) => void; onClose: () => void;
}) {
  const [reason, setReason] = useState("");
  const needsReason = !!reasonLabel;
  const blocked = busy || (needsReason && !reason.trim());
  return (
    <Dialog title={title} onClose={() => { if (!busy) onClose(); }}>
      <form onSubmit={(e) => { e.preventDefault(); if (!blocked) onConfirm(reason.trim()); }}>
        <p className={styles.dialogSub}>{message}</p>
        {needsReason && (
          <>
            <label className={styles.fieldLabel} htmlFor="confirm-reason">{reasonLabel}</label>
            <input id="confirm-reason" className={styles.input} value={reason} maxLength={500} autoFocus required
              onChange={(e) => setReason(e.target.value)} />
          </>
        )}
        {error && <div className={styles.errorInline} role="alert">{error}</div>}
        <div className={styles.dialogActions}>
          <button type="button" className={styles.secondaryBtn} disabled={busy} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.primaryBtn} disabled={blocked}>{busy ? "Working…" : confirmLabel}</button>
        </div>
      </form>
    </Dialog>
  );
}
