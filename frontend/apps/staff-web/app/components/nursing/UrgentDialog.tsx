"use client";

import styles from "./nursing.module.css";
import { useState } from "react";
import Dialog from "../ui/Dialog";
import { api } from "../../lib/api";
import { URGENT_REASON_CODES, detailOf, type Row } from "./model";

// FEF-031: "Mark urgent" (same catalogue as arrivals' Mark priority); mounted only while open, so its state resets.
export default function UrgentDialog({ initialEntryId, candidates, onClose, onDone }: { initialEntryId: string; candidates: Row[]; onClose: () => void; onDone: () => void }) {
  const [urgentEntryId, setUrgentEntryId] = useState(initialEntryId);
  const [urgentReasonCode, setUrgentReasonCode] = useState<string>(URGENT_REASON_CODES[0]);
  const [urgentOtherReason, setUrgentOtherReason] = useState("");
  const [urgentSubmitting, setUrgentSubmitting] = useState(false);
  const [urgentError, setUrgentError] = useState<string | null>(null);
  const urgentCandidates = candidates;
  async function submitMarkUrgent(e: React.FormEvent) {
    e.preventDefault();
    if (!urgentEntryId) return;
    const reason = urgentReasonCode === "Other" ? urgentOtherReason.trim() : urgentReasonCode;
    if (!reason) return;
    setUrgentSubmitting(true);
    setUrgentError(null);
    try {
      await api.post(`/queue/${urgentEntryId}/reorder`, { priority: true, reason });
      onDone();
    } catch (err) {
      setUrgentError(detailOf(err, "Couldn't flag this patient."));
    } finally {
      setUrgentSubmitting(false);
    }
  }
  return (
        <Dialog title="Mark urgent" onClose={() => { if (!urgentSubmitting) onClose(); }}>
          <form onSubmit={submitMarkUrgent}>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="urgentPatient">Patient</label>
              <select id="urgentPatient" className={styles.select} value={urgentEntryId} onChange={(e) => setUrgentEntryId(e.target.value)}>
                <option value="">Select a patient…</option>
                {urgentCandidates.map((r) => <option key={r.id} value={r.id}>{r.patientName}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="urgentReason">Reason</label>
              <select id="urgentReason" className={styles.select} value={urgentReasonCode} onChange={(e) => setUrgentReasonCode(e.target.value)}>
                {URGENT_REASON_CODES.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            {urgentReasonCode === "Other" && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="urgentOther">Specify</label>
                <input id="urgentOther" className={styles.input} value={urgentOtherReason} onChange={(e) => setUrgentOtherReason(e.target.value)} />
              </div>
            )}
            {urgentError && <div className={styles.formError} role="alert">{urgentError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => onClose()}>Cancel</button>
              <button type="submit" className={styles.submitBtn}
                disabled={urgentSubmitting || !urgentEntryId || (urgentReasonCode === "Other" && !urgentOtherReason.trim())}>
                {urgentSubmitting ? "Flagging…" : "Mark urgent"}
              </button>
            </div>
          </form>
        </Dialog>
  );
}
