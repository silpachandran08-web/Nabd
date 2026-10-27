"use client";

import styles from "./nursing.module.css";
import { EmptyState } from "../ui/StateViews";
import { hhmm, type ActivityEntry } from "./model";

// FEF-031: Completed Activity tab (NB-148), moved unchanged from the old page.
export default function ActivityTab({ activity }: { activity: ActivityEntry[] }) {
  function exportActivityCsv() {
    const header = "Kind,Activity,Patient,Occurred At\n";
    const body = activity.map((a) => [a.kind, a.activity, a.patientName, a.occurredAt].map((v) => `"${v}"`).join(",")).join("\n");
    const blob = new Blob([header + body], { type: "text/csv" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `shift-handover-${new Date().toLocaleDateString("en-CA")}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
  return (
        <>
          <div className={`${styles.headerActions} ${styles.actionsGap}`}>
            <button className={styles.smallBtn} onClick={exportActivityCsv} disabled={activity.length === 0}>Export for handover</button>
          </div>
          <div className={styles.card}>
            {activity.length === 0 ? (
              <EmptyState icon="check" title="Nothing completed yet today" hint="Completed vitals, administrations and procedures appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th scope="col">Patient</th><th scope="col">Activity</th><th scope="col">Time</th></tr></thead>
                  <tbody>
                    {activity.map((a, i) => (
                      <tr key={i}>
                        <td data-label="Patient">{a.patientName}</td>
                        <td data-label="Activity">{a.activity}</td>
                        <td data-label="Time" className={styles.muted}>{hhmm(a.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
  );
}
