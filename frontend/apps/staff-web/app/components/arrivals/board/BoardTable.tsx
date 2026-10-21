"use client";

import styles from "../arrivals.module.css";
import { useRenderProbe } from "../renderProbe";
import type { Grants, Row, Staff } from "../types";
import ArrivalRow from "./ArrivalRow";
import type { RowHandlers } from "./RowActions";

export default function BoardTable({ rows, staff, grants, sendingId, h, onOpen }: {
  rows: Row[]; staff: Staff[]; grants: Grants; sendingId?: string; h: RowHandlers; onOpen: (id: string) => void;
}) {
  useRenderProbe("arrivals-board");
  return (
    <div className={styles.tableWrap}>
      <table className={styles.table}>
        <thead>
          <tr><th>Token</th><th>Patient</th><th className={styles.colVisit}>Visit</th><th className={styles.colDoctor}>Doctor</th><th>Wait</th><th>Status</th><th><span className={styles.srOnly}>Actions</span></th></tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <ArrivalRow key={r.id} row={r} doctorName={staff.find((s) => s.id === r.doctorId)?.name ?? "—"} grants={grants} sendingId={sendingId} h={h} onOpen={onOpen} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
