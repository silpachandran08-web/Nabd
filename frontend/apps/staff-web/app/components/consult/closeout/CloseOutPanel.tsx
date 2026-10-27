"use client";

import { useTranslations } from "next-intl";
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
  const tx = useTranslations("consult.closeOutPanel");
  const g = useTranslations("glossary");
  const { hasDrugs, issues, handoffPending, closedOut } = view;
  return (
    <section className={p.card} aria-label={tx("closeConsultation")}>
      <h3 className={p.cardTitle}>{tx("closeConsultation")}</h3>
      <div className={p.stack}>
        <ul className={p.list}>
          <li className={p.listRow}>
            <strong>{tx("note")}</strong>
            {noteSigned ? <StatusPill tone="ok" label={tx("signed")} /> : <StatusPill tone="warn" label={tx("draftWillBeSigned")} />}
            <span className={p.muted}>{tx("diagnosis")}{" "}{diagnosis.trim() || tx("notEntered")}</span>
          </li>
          <li className={p.listRow}>
            <strong>{g("rx.prescription")}</strong>
            {!hasDrugs ? <StatusPill tone="neutral" icon="list" label={tx("none")} />
              : rxStatus === "signed" ? <StatusPill tone="ok" label={tx("signed")} />
              : <StatusPill tone="danger" label={tx("notSigned")} />}
            {hasDrugs && <span className={p.muted}>{rxItems.filter((i) => i.drugName.trim()).map((i) => i.drugName).join(", ")}</span>}
            {hasDrugs && rxStatus !== "signed" && <button type="button" className={p.linkBtn} onClick={onGoToRx}>{tx("goToPrescription")}</button>}
          </li>
          <li className={p.listRow}>
            <strong>{tx("followUp")}</strong>
            {followUpLabel ? <StatusPill tone="ok" label={tx("followUpSet", { label: followUpLabel })} />
              : followUpLabel === null ? <StatusPill tone="neutral" icon="calendar" label={tx("notSet")} />
              : <StatusPill tone="warn" icon="alert" label={tx("unknownCouldnTCheck")} />}
          </li>
          <li className={p.listRow}>
            <strong>{tx("checkout")}</strong>
            <span className={p.muted}>{tx("visibleToReceptionAsSoon")}</span>
          </li>
        </ul>
        <VisitOutputs authedFetch={authedFetch} queueEntryId={entry.id} patientName={entry.patientName} patient={patient} />

        {issues.length > 0 && !closedOut && (
          <div className={`${p.notice} ${p.noticeDanger}`} role="alert">
            <Icon name="alert" size={16} />
            <span>
              {tx("checkBeforeClosing")}
              <ul className={p.noticeList}>{issues.map((id) => <li key={id}>{tx(`issue.${id}`)}</li>)}</ul>
              <label className={`${p.check} ${p.mtS}`}>
                <input type="checkbox" checked={closeAck} onChange={(e) => onAck(e.target.checked)} />
                {tx("iUnderstandAndWantTo")}
              </label>
            </span>
          </div>
        )}
        {handoffPending && (
          <div className={`${p.notice} ${p.noticeWarn}`} role="status">
            <Icon name="alert" size={16} />
            <span>{tx("theNoteIsSignedBut")}</span>
          </div>
        )}
        {closedOut && <div className={`${p.notice} ${p.noticeOk}`} role="status"><Icon name="check" size={16} /><span>{tx("sentToCheckout")}</span></div>}
      </div>
    </section>
  );
}
