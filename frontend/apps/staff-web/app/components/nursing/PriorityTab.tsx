"use client";

import styles from "./nursing.module.css";
import StatusPill from "../ui/StatusPill";
import { EmptyState } from "../ui/StateViews";
import { api } from "../../lib/api";
import { detailOf, hhmm, statusLabel, type Row } from "./model";

// FEF-031: the Priority Patients tab (NB-143), moved unchanged from the old page.
export default function PriorityTab({ priorityRows, queueForbidden, noQueueAccess, canFlagPriority, staffName, invalidateAll, setActionError }: {
  priorityRows: Row[]; queueForbidden: boolean; noQueueAccess: React.ReactNode; canFlagPriority: boolean;
  staffName: (id: string | null | undefined) => string | null; invalidateAll: () => Promise<unknown>; setActionError: (m: string | null) => void;
}) {
  async function acknowledgePriority(id: string) {
    setActionError(null);
    await api.post(`/queue/${id}/priority/acknowledge`).catch((err) => setActionError(detailOf(err, "Couldn't acknowledge.")));
    void invalidateAll();
  }
  return (
        <div className={styles.card}>
          {queueForbidden ? noQueueAccess : priorityRows.length === 0 ? (
            <EmptyState icon="alert" title="No priority patients flagged" hint="Patients flagged urgent by reception or nursing appear here." />
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr><th scope="col">Patient</th><th scope="col">Flagged</th><th scope="col">Reason</th><th scope="col">Acknowledged</th><th scope="col">State</th><th scope="col"></th></tr></thead>
                <tbody>
                  {priorityRows.map((r) => {
                    const flaggedBy = staffName(r.priorityFlaggedBy);
                    const ackBy = staffName(r.priorityAcknowledgedBy);
                    return (
                      <tr key={r.id} className={styles.rowUrgent}>
                        <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                        <td data-label="Flagged" className={styles.muted}>
                          {r.priorityFlaggedAt ? hhmm(r.priorityFlaggedAt) : "—"}{flaggedBy ? ` · by ${flaggedBy}` : ""}
                        </td>
                        <td data-label="Reason">{r.priorityReason}</td>
                        <td data-label="Acknowledged" className={styles.muted}>
                          {r.priorityAcknowledgedAt
                            ? <>{ackBy ? `${ackBy} · ` : ""}{hhmm(r.priorityAcknowledgedAt)}</>
                            : <StatusPill tone="urgent" label="Awaiting acknowledgement" icon="clock" />}
                        </td>
                        <td data-label="State"><StatusPill tone={r.priorityAcknowledgedAt ? "info" : "neutral"} label={statusLabel(r.status)} icon={r.priorityAcknowledgedAt ? "check" : "list"} /></td>
                        <td data-label="">{!r.priorityAcknowledgedAt && canFlagPriority && <button className={styles.actionBtn} onClick={() => acknowledgePriority(r.id)}>Acknowledge</button>}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
  );
}
