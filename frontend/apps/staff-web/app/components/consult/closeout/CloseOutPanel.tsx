"use client";

import Icon from "../../ui/Icon";
import StatusPill from "../../ui/StatusPill";
import type { AuthedFetch, PatientDetail, PrescriptionItem, Row } from "../shared";
import VisitOutputs from "../VisitOutputs";
import type { CloseView } from "./closeLogic";
import p from "../consultParts.module.css";

type Props = {
  entry: Row; patient: PatientDetail | null; authedFetch: AuthedFetch; view: CloseView; noteSigned: boolean; diagnosis: string;
  rxItems: PrescriptionItem[]; rxStatus: "draft" | "signed"; followUpLabel: string | null | undefined;
  closeAck: boolean; onAck: (v: boolean) => void; onGoToRx: () => void;
};

// FEF-030 (DOC-029): the Close step: what stays open after this visit.
export default function CloseOutPanel({ entry, patient, authedFetch, view, noteSigned, diagnosis, rxItems, rxStatus, followUpLabel, closeAck, onAck, onGoToRx }: Props) {
  const { hasDrugs, issues, handoffPending, closedOut } = view;
  return (
    <section className={p.card} aria-label="Close consultation">
      <h3 className={p.cardTitle}>Close consultation</h3>
      <div className={p.stack}>
        <ul className={p.list}>
          <li className={p.listRow}>
            <strong>Note</strong>
            {noteSigned ? <StatusPill tone="ok" label="Signed" /> : <StatusPill tone="warn" label="Draft — will be signed" />}
            <span className={p.muted}>Diagnosis: {diagnosis.trim() || "not entered"}</span>
          </li>
          <li className={p.listRow}>
            <strong>Prescription</strong>
            {!hasDrugs ? <StatusPill tone="neutral" icon="list" label="None" />
              : rxStatus === "signed" ? <StatusPill tone="ok" label="Signed" />
              : <StatusPill tone="danger" label="Not signed" />}
            {hasDrugs && <span className={p.muted}>{rxItems.filter((i) => i.drugName.trim()).map((i) => i.drugName).join(", ")}</span>}
            {hasDrugs && rxStatus !== "signed" && <button type="button" className={p.linkBtn} onClick={onGoToRx}>Go to prescription</button>}
          </li>
          <li className={p.listRow}>
            <strong>Follow-up</strong>
            {followUpLabel ? <StatusPill tone="ok" label={`Set · ${followUpLabel}`} />
              : followUpLabel === null ? <StatusPill tone="neutral" icon="calendar" label="Not set" />
              : <StatusPill tone="warn" icon="alert" label="Unknown — couldn't check" />}
          </li>
          <li className={p.listRow}>
            <strong>Checkout</strong>
            <span className={p.muted}>Visible to reception as soon as you close.</span>
          </li>
        </ul>
        <VisitOutputs authedFetch={authedFetch} queueEntryId={entry.id} patientName={entry.patientName} patient={patient} />

        {issues.length > 0 && !closedOut && (
          <div className={`${p.notice} ${p.noticeDanger}`} role="alert">
            <Icon name="alert" size={16} />
            <span>
              Check before closing:
              <ul className={p.noticeList}>{issues.map((t) => <li key={t}>{t}</li>)}</ul>
              <label className={`${p.check} ${p.mtS}`}>
                <input type="checkbox" checked={closeAck} onChange={(e) => onAck(e.target.checked)} />
                I understand and want to close anyway.
              </label>
            </span>
          </div>
        )}
        {handoffPending && (
          <div className={`${p.notice} ${p.noticeWarn}`} role="status">
            <Icon name="alert" size={16} />
            <span>The note is signed but the patient has not reached checkout yet.</span>
          </div>
        )}
        {closedOut && <div className={`${p.notice} ${p.noticeOk}`} role="status"><Icon name="check" size={16} /><span>Sent to checkout.</span></div>}
      </div>
    </section>
  );
}
