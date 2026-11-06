"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import Dialog from "../ui/Dialog";
import AllergyRegister from "./AllergyRegister";
import { queueStatusLabel } from "@nabd/domain";
import StatusPill from "../ui/StatusPill";
import { Skeleton } from "../ui/StateViews";
import { useClinicalContext } from "./clinical";
import MedicinesList from "./MedicinesList";
import SafetyStrip from "./SafetyStrip";
import VitalsCard from "./VitalsCard";
import { age, type AuthedFetch, type Row } from "./shared";
import s from "./consultParts.module.css";

/** Pre-consult summary (DOC-024): shown between "Start consultation" and the status change, and as
 * the read-only "Summary" for visits that are not startable. */
export default function PreConsultDialog({
  row, authedFetch, onClose, onStart, onOpenVisit, starting, error, canEditAllergies = false,
}: {
  row: Row; authedFetch: AuthedFetch; onClose: () => void;
  onStart?: () => void; onOpenVisit?: () => void; starting: boolean; error: string | null;
  /** clinical:edit — the "Add allergy" action is absent without it. */
  canEditAllergies?: boolean;
}) {
  const tx = useTranslations("consult.preConsultDialog");
  const { ctx, reload } = useClinicalContext(authedFetch, row.patientId, row.id);
  const [showAllergies, setShowAllergies] = useState(false);
  const p = row.patient;
  const recent = ctx.timeline;

  return (
    <>
    <Dialog title={tx("title", { patientName: row.patientName, tokenNumber: row.tokenNumber })} onClose={onClose} size="lg">
      <div className={s.stack}>
        <SafetyStrip patient={p} ctx={ctx} onRetry={reload} sticky={false} />

        <div className={s.statGrid}>
          <div className={s.stat}><span className={s.safetyLabel}>{tx("visitReason")}</span>{row.presentingComplaint?.trim() || tx("notRecorded")}</div>
          <div className={s.stat}><span className={s.safetyLabel}>{tx("patient")}</span>{p ? `${age(p.dob)} · ${p.gender} · ${p.mrn}` : "—"}</div>
          <div className={s.stat}><span className={s.safetyLabel}>{tx("package")}</span>{p ? (p.activePackages > 0 ? `${p.activePackages} active` : "None") : "—"}</div>
          <div className={s.stat}><span className={s.safetyLabel}>{tx("status")}</span>{queueStatusLabel(row.status)}</div>
        </div>

        <VitalsCard vitals={ctx.vitals} onRetry={reload} />

        <section className={s.card} aria-label={tx("lastVisits")}>
          <h3 className={s.cardTitle}>{tx("lastThreeVisits")}</h3>
          {recent.status === "loading" && <Skeleton rows={2} label={tx("loadingVisits")} />}
          {recent.status === "error" && <p className={s.muted}>{tx("couldnTLoadPreviousVisits")}{" "}<button type="button" className={s.linkBtn} onClick={reload}>{tx("retry")}</button></p>}
          {(recent.status === "none" || (recent.status === "ok" && (recent.data ?? []).length === 0)) && <p className={s.muted}>{tx("noPreviousVisitsRecorded")}</p>}
          {recent.status === "ok" && (recent.data ?? []).length > 0 && (
            <ul className={s.list}>
              {(recent.data ?? []).map((e) => (
                <li key={e.queueEntryId} className={s.listRow}>
                  <span className={s.muted}>{new Date(e.occurredAt).toLocaleDateString()}</span>
                  <span>{e.diagnosis || e.assessment || "—"}</span>
                  {e.medications && <span className={s.muted}>{e.medications}</span>}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className={s.card} aria-label={tx("previousMedicines")}>
          <h3 className={s.cardTitle}>{tx("previousMedicines")}</h3>
          <MedicinesList prescriptions={ctx.prescriptions} onRetry={reload} />
          <p className={`${s.muted} ${s.mtS}`}>{tx("whetherTheLastPrescriptionWas")}</p>
        </section>

        {error && <div className={`${s.notice} ${s.noticeDanger}`} role="alert">{error}</div>}
      </div>
      <div className={s.dialogActions}>
        {onStart && <StatusPill tone="info" label={tx("readyForDoctor")} />}
        <button type="button" className={s.btn} onClick={onClose}>{tx("close")}</button>
        {canEditAllergies && p && <button type="button" className={s.btn} onClick={() => setShowAllergies(true)}>{tx("addAllergy")}</button>}
        {onOpenVisit && <button type="button" className={s.btn} onClick={onOpenVisit}>{tx("openVisitRecord")}</button>}
        {onStart && <button type="button" className={s.btnPrimary} onClick={onStart} disabled={starting}>{starting ? tx("starting") : tx("startConsultation")}</button>}
      </div>
    </Dialog>
      {showAllergies && p && (
        <AllergyRegister authedFetch={authedFetch} patientId={p.id} patientName={p.name} canEdit={canEditAllergies} draftKey={p.id}
          onClose={() => setShowAllergies(false)} onChanged={reload} />
      )}
    </>
  );
}
