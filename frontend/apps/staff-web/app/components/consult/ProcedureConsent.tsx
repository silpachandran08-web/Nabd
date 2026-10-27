"use client";

import { useTranslations } from "next-intl";
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
  const tx = useTranslations("consult.procedureConsent");
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
        setError(p.detail || tx("consentWasNotRecordedTry"));
        return;
      }
      onRecorded((await res.json()) as ProcOrder);
    } catch {
      setError(tx("couldnTReachTheServer"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={tx("recordProcedureConsent")} onClose={onClose}>
      <div className={s.stack}>
        <p className={s.muted}>{order.patientName} · {order.chargeName}</p>
        <div className={`${s.notice} ${s.noticeDanger}`}>
          <Icon name="alert" size={16} />
          <span>{tx("hardGateThisProcedureCannot")}</span>
        </div>
        {order.consentNote ? (
          <div>
            <strong className={s.muted}>{tx("consentNoteEnteredBy", { orderedByName: order.orderedByName })}</strong>
            <p className={s.complaint}>{order.consentNote}</p>
          </div>
        ) : (
          <div className={`${s.notice} ${s.noticeWarn}`} role="status">
            <Icon name="alert" size={16} />
            <span>{tx("noConsentWordingWasEntered")}</span>
          </div>
        )}
        <div className={s.field}>
          <label htmlFor="pcName">{tx("nameOfThePersonGiving")}</label>
          <input id="pcName" className={s.input} value={name} onChange={(e) => setName(e.target.value)} autoComplete="off" autoFocus />
        </div>
        <label className={s.check}>
          <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
          <span>{tx("theProcedureWasExplainedAnd")}</span>
        </label>
        <p className={s.muted}>{tx("recordedTheTypedNameYour")}</p>
        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert"><Icon name="alert" size={16} /><span>{error}</span></div>}
      </div>
      <div className={s.dialogActions}>
        <button type="button" className={s.btn} onClick={onClose}>{tx("cancel")}</button>
        <button type="button" className={s.btnPrimary} onClick={submit} disabled={busy || !name.trim() || !confirmed}>{busy ? tx("recording") : tx("recordConsent")}</button>
      </div>
    </Dialog>
  );
}
