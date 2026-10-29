"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "../../(clinic)/setup/setup.module.css";
import ph from "./pharmacy.module.css";
import Dialog from "../ui/Dialog";
import { ErrorState, Skeleton } from "../ui/StateViews";
import { api, errorText } from "../../lib/api";
import { LOSS_REMINDER, REASONS, lossReminder, type ReasonCode } from "../../lib/stockTake";
import ApproverStep, { type Approval } from "./ApproverStep";
import type { Full } from "../../lib/gen";
import type { StockBatch } from "@nabd/api-client/generated";

type Batch = Full<StockBatch, "batchNo" | "expiryDate" | "location" | "mrp" | "purchaseRate">;

/** PHL-010 overlay:phadjust: change one batch by a delta or to a counted total, with a reason from the fixed list.
 * A controlled drug also needs the second signer. */
export default function BatchAdjustDialog({ item, controlled, onClose, onSaved }: {
  item: { id: string; name: string }; controlled: boolean; onClose: () => void; onSaved: (text: string) => void;
}) {
  const [batches, setBatches] = useState<Batch[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [batchId, setBatchId] = useState("");
  const [mode, setMode] = useState<"delta" | "setTo">("delta");
  const [qty, setQty] = useState("");
  const [reasonCode, setReasonCode] = useState<ReasonCode | "">("");
  const [note, setNote] = useState("");
  const [approval, setApproval] = useState<Approval>({ approverStaffId: "", approverPin: "" });
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const b = await api.get<Batch[]>(`/pharmacy/items/${item.id}/batches`);
      setBatches(b);
      if (b.length === 1) setBatchId(b[0].batchId);
    } catch (e) { setLoadError(errorText(e, "Couldn't load the batches.")); }
  }, [item.id]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const batch = batches?.find((b) => b.batchId === batchId);
  const n = /^-?\d{1,8}$/.test(qty.trim()) ? Number(qty) : null;
  const delta = n == null || !batch ? null : mode === "delta" ? n : n - batch.qtyOnHand;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!batch) { setProblem("Choose the batch."); return; }
    if (delta == null || (mode === "setTo" && (n ?? -1) < 0)) { setProblem("Enter a whole number."); return; }
    if (delta === 0) { setProblem("That is no change."); return; }
    if (batch.qtyOnHand + delta < 0) { setProblem(`Only ${batch.qtyOnHand} in this batch.`); return; }
    if (!reasonCode) { setProblem("Choose a reason."); return; }
    if (reasonCode === "other" && note.trim() === "") { setProblem("Reason \"Other\" needs a note."); return; }
    if (controlled && (!approval.approverStaffId || !approval.approverPin)) { setProblem("The second signer must choose their name and enter their PIN."); return; }
    setBusy(true); setProblem(null);
    try {
      await api.post(`/pharmacy/batches/${batch.batchId}/adjustments`, {
        ...(mode === "delta" ? { delta: n } : { setTo: n }), reasonCode, note: note.trim() || undefined,
        ...(controlled ? approval : {}),
      });
      onSaved(`Stock of ${item.name} updated.`);
    } catch (err) {
      setProblem(errorText(err, "Couldn't save the change. Nothing was changed."));
      setApproval({ ...approval, approverPin: "" });
    } finally { setBusy(false); }
  };

  return (
    <Dialog title={`Adjust by batch · ${item.name}`} size="drawer" onClose={onClose}>
      {loadError ? <ErrorState message={loadError} onRetry={load} /> : !batches ? <Skeleton rows={3} label="Loading batches" /> : batches.length === 0 ? (
        <p className={styles.subtitle}>This item has no batch with stock. Receive stock first.</p>
      ) : (
        <form onSubmit={submit}>
          <p className={styles.subtitle}>Every change is a permanent register entry with who, when and why.</p>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="ba-batch">Batch</label>
            <select id="ba-batch" className={styles.select} value={batchId} onChange={(e) => setBatchId(e.target.value)}>
              <option value="">Choose…</option>
              {batches.map((b) => <option key={b.batchId} value={b.batchId}>{[b.batchNo, b.expiryDate, b.location].filter(Boolean).join(" · ") || "Stock"} — {b.qtyOnHand} on hand</option>)}
            </select>
          </div>
          <div className={ph.formRow}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ba-mode">Type of change</label>
              <select id="ba-mode" className={styles.select} value={mode} onChange={(e) => setMode(e.target.value as "delta" | "setTo")}>
                <option value="delta">Add or remove units (+/−)</option>
                <option value="setTo">Set to a counted total</option>
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ba-qty">{mode === "delta" ? "Change (negative to remove)" : "Counted total"}</label>
              <input id="ba-qty" className={styles.input} inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value)} />
            </div>
          </div>
          <div className={ph.formRow}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ba-reason">Reason</label>
              <select id="ba-reason" className={styles.select} value={reasonCode} onChange={(e) => setReasonCode(e.target.value as ReasonCode)}>
                <option value="">Choose…</option>
                {REASONS.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="ba-note">Note{reasonCode === "other" ? " (required)" : " (optional)"}</label>
              <input id="ba-note" className={styles.input} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
          </div>
          {lossReminder(controlled, delta) && <div className={ph.warn} role="note">{LOSS_REMINDER}</div>}
          {controlled && <ApproverStep idPrefix="ba" value={approval} onChange={setApproval} />}
          {problem && <div className={`${ph.notice} ${ph.noticeError}`} role="alert">{problem}</div>}
          <div className={styles.actions}>
            <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
            <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Record change"}</button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
