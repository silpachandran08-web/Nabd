"use client";

import { useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import type { AuthedFetch, Problem } from "./shared";
import type { ProcOrder } from "./doctorOrders";
import s from "./consultParts.module.css";

// DOC-040 procedure consent gate. POST /nursing/procedure-orders/{id}/consent (nursing:edit) records
// ONE thing: the signed name, with who recorded it and when. The system does not store a form
// version, language, guardian or an image of a signature, so none of those are asked for or implied here.
// The consent wording is whatever the ordering doctor typed in the order's consent note; the confirm
// sentence below is a neutral attestation and still needs clinical/legal sign-off by the clinic.
export default function ProcedureConsent({
  authedFetch, order, onClose, onRecorded,
}: { authedFetch: AuthedFetch; order: ProcOrder; onClose: () => void; onRecorded: (o: ProcOrder) => void }) {
  const [name, setName] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!name.trim() || !confirmed) return;
    setBusy(true);
    setError(null);
    try {
      const res = await authedFetch(`/nursing/procedure-orders/${order.id}/consent`, { method: "POST", body: JSON.stringify({ signedName: name.trim() }) });
      if (!res) return;
      if (!res.ok) {
        // Stay open: consent that failed to record must not look recorded.
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "" }));
        setError(p.detail || "Consent was not recorded. Try again.");
        return;
      }
      onRecorded((await res.json()) as ProcOrder);
    } catch {
      setError("Couldn't reach the server. Consent was not recorded.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title="Record procedure consent" onClose={onClose}>
      <div className={s.stack}>
        <p className={s.muted}>{order.patientName} · {order.chargeName}</p>
        <div className={`${s.notice} ${s.noticeDanger}`}>
          <Icon name="alert" size={16} />
          <span>Hard gate: this procedure cannot be marked ready or completed until consent is recorded.</span>
        </div>
        {order.consentNote ? (
          <div>
            <strong className={s.muted}>Consent note entered by {order.orderedByName}</strong>
            <p className={s.complaint}>{order.consentNote}</p>
          </div>
        ) : (
          <div className={`${s.notice} ${s.noticeWarn}`} role="status">
            <Icon name="alert" size={16} />
            <span>No consent wording was entered with this order. The clinic must supply its approved procedure consent text (clinical and legal sign-off) before relying on this record.</span>
          </div>
        )}
        <div className={s.field}>
          <label htmlFor="pcName">Name of the person giving consent (patient or guardian)</label>
          <input id="pcName" className={s.input} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" autoFocus />
        </div>
        <label className={s.check}>
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
          <span>The procedure was explained and the person named above agreed to it.</span>
        </label>
        <p className={s.muted}>Recorded: the typed name, your name and the time. Not recorded yet: form version, language, guardian verification or a handwritten signature.</p>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>Cancel</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !name.trim() || !confirmed}>{busy ? "Recording…" : "Record consent"}</button>
      </div>
    </Dialog>
  );
}
