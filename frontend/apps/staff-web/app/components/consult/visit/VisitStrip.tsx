"use client";

import type { ReactNode } from "react";
import { age, initials, type PatientDetail, type Row } from "../shared";
import ConsultTimer from "../ConsultTimer";
import StatusBadge from "../queue/StatusBadge";
import styles from "../consult.module.css";

// FEF-030: the patient banner. It sits outside every PanelBoundary, so it stays on screen when a panel fails.
export default function VisitStrip({ entry, patient, badge, timer, onBack }: { entry: Row; patient: PatientDetail | null; badge: ReactNode; timer: { show: boolean; ended: boolean }; onBack: () => void }) {
  return (
    <div className={styles.strip} data-testid="patient-banner">
      <div className={styles.stripIdentity}>
        <button className={styles.backBtn} onClick={onBack}>Queue</button>
        {patient && <span className={styles.avatar} aria-hidden="true">{initials(patient.name)}</span>}
        <span className={styles.stripText}>
          {patient && (
            <>
              <span className={styles.stripName}>{patient.name}</span>
              <span className={styles.stripMeta}>{patient.mrn} · {age(patient.dob)} · {patient.gender} · Token #{entry.tokenNumber}</span>
            </>
          )}
          {entry.presentingComplaint?.trim() && <span className={styles.stripComplaint}>Reason: {entry.presentingComplaint}</span>}
        </span>
        <StatusBadge status={entry.status} />
        {badge}
        {timer.show && <ConsultTimer entryId={entry.id} ended={timer.ended} />}
      </div>
    </div>
  );
}
