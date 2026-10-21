import type { CallbackEntryResponse } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import StatusPill from "../../ui/StatusPill";
import { EmptyState, ErrorState } from "../../ui/StateViews";
import type { DoctorQueue, Grants } from "../types";

export default function SideColumn({ doctors, rosterFailed, onRetryRoster, callbacks, grants, onAnnounce, onClear }: {
  doctors: DoctorQueue[]; rosterFailed: boolean; onRetryRoster: () => void; callbacks: CallbackEntryResponse[]; grants: Grants;
  onAnnounce: (doctorId: string) => void; onClear: (doctorId: string) => void;
}) {
  return (
    <div className={styles.sideCol}>
      <div className={styles.card}>
        <div className={styles.sideTitle}>Doctor queues</div>
        {rosterFailed ? (
          <ErrorState message="Couldn't load the doctor roster." onRetry={onRetryRoster} />
        ) : doctors.length === 0 ? (
          <EmptyState icon="stethoscope" title="No doctors on the roster" />
        ) : (
          doctors.map((d) => (
            <div key={d.id} className={`${styles.doctorRow} ${styles.doctorRowStack}${d.waiting === 0 ? ` ${styles.doctorIdle}` : ""}`}>
              <div className={styles.doctorLine}>
                <span>
                  <span className={styles.doctorName}>{d.name}</span>
                  {d.departmentName && <span className={styles.doctorDept}>{d.departmentName}</span>}
                </span>
                <span className={styles.doctorCount}>
                  {d.waiting === 0 ? "No patients waiting" : `${d.waiting} waiting${d.estimatedMinutes !== undefined ? ` · ~${d.estimatedMinutes}m wait` : ""}`}
                </span>
              </div>
              {d.delay ? (
                <div className={styles.delayLine}>
                  <span><StatusPill tone="danger" label="Delayed" icon="clock" /> Running {d.delay.delayMinutes}m late{d.delay.reason ? ` — ${d.delay.reason}` : ""}</span>
                  {grants.queueEdit && <button type="button" className={styles.actionBtn} onClick={() => onClear(d.id)}>Clear delay</button>}
                </div>
              ) : grants.queueEdit && (
                <button type="button" className={`${styles.actionBtn} ${styles.alignStart}`} onClick={() => onAnnounce(d.id)}>Announce delay</button>
              )}
            </div>
          ))
        )}
      </div>

      {callbacks.length > 0 && (
        <div className={styles.card}>
          <div className={styles.sideTitle}>Follow-up callback list</div>
          {callbacks.map((c) => (
            <div key={c.appointmentId} className={`${styles.doctorRow} ${styles.doctorRowStack}`}>
              <div className={styles.doctorLine}>
                <span className={styles.doctorName}>{c.patientName}</span>
                {c.status === "no_show" ? <StatusPill tone="danger" label="Missed" /> : <StatusPill tone="warn" label="Overdue" />}
              </div>
              <span className={styles.callbackNote}>Follow-up was {new Date(c.startTime ?? "").toLocaleDateString()}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
