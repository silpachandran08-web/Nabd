"use client";

import { VISIT_LABEL } from "@nabd/domain";
import StatusPill from "../../ui/StatusPill";
import StatusBadge from "../queue/StatusBadge";
import type { Row } from "../shared";
import styles from "../consult.module.css";

// FEF-030: "Today's patients": switch to another visit (the workspace saves the open note first).
export default function TodayAside({ rows, activeId, onSwitch }: { rows: Row[]; activeId: string; onSwitch: (r: Row) => void }) {
  return (
    <aside className={styles.today} aria-label="Today's patients">
      <h2 className={styles.todayTitle}>Today&apos;s patients</h2>
      <ul className={styles.todayList}>
        {rows.map((r) => (
          <li key={r.id}>
            <button type="button" className={`${styles.todayBtn} ${r.id === activeId ? styles.todayBtnOn : ""}`} aria-current={r.id === activeId ? "true" : undefined} onClick={() => onSwitch(r)}>
              <span><strong>#{r.tokenNumber}</strong> {r.patientName}</span>
              <span className={styles.todayMeta}>{r.appointmentId ? "Appointment" : (VISIT_LABEL[r.source ?? ""] ?? "Walk-in")}</span>
              <span className={styles.flags}>
                {(r.patient?.allergies.length ?? 0) > 0 && <StatusPill tone="danger" label="Allergy" />}
                {r.priority && <StatusPill tone="urgent" label="Urgent" />}
                <StatusBadge status={r.status} />
              </span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}
