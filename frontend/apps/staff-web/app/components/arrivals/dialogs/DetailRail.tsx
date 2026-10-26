"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { VISIT_LABEL } from "@nabd/domain";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import StatusPill from "../../ui/StatusPill";
import { ErrorState, Skeleton } from "../../ui/StateViews";
import { bucketOf, VISIT_TYPE_LABEL } from "../board/boardLogic";
import RowActions, { type RowHandlers } from "../board/RowActions";
import StatusBadge from "../board/StatusBadge";
import WaitCell from "../board/WaitCell";
import { patientDetailQuery } from "../hooks/useArrivals";
import ReackPrompt from "../ReackPrompt";
import type { Grants, Row } from "../types";

// Detail rail: the same cached patient query the board row uses (revalidated when the rail opens).
export default function DetailRail({ row, doctorName, grants, sendingId, h, onClose }: {
  row: Row; doctorName: string; grants: Grants; sendingId?: string; h: RowHandlers; onClose: () => void;
}) {
  const patientQ = useQuery(patientDetailQuery(row.patientId));
  const p = patientQ.data;
  const b = bucketOf(row.status);
  return (
    <Dialog size="drawer" title={`#${row.tokenNumber} · ${row.patientName}`} onClose={onClose}>
      <div className={styles.railStatus}>
        <StatusBadge status={row.status} />
        {row.priority && <StatusPill tone="urgent" label="Priority" />}
      </div>
      {row.priority && row.priorityReason && <p className={styles.railNote}>Priority reason: {row.priorityReason}</p>}
      <dl className={styles.railList}>
        <div><dt>MRN</dt><dd>{row.patientMrn || "—"}</dd></div>
        <div><dt>Visit</dt><dd>{(row.visitType && VISIT_TYPE_LABEL[row.visitType]) || (VISIT_LABEL[row.source] ?? row.source)}</dd></div>
        <div><dt>Doctor</dt><dd>{doctorName}</dd></div>
        <div><dt>Waiting</dt><dd>{b === "waiting" || b === "in_consult" ? <WaitCell createdAt={row.createdAt} unit=" min" /> : "—"}</dd></div>
        <div><dt>Arrived</dt><dd>{new Date(row.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</dd></div>
      </dl>
      <div className={styles.sideTitle} style={{ paddingInline: 0 }}>Patient</div>
      {!p && !patientQ.isError ? (
        <Skeleton rows={3} label="Loading patient" />
      ) : !p ? (
        <ErrorState message="Couldn't load the patient record." onRetry={() => void patientQ.refetch()} />
      ) : (
        <dl className={styles.railList}>
          <div><dt>Phone</dt><dd>{p.phone || "—"}</dd></div>
          <div><dt>Date of birth</dt><dd>{p.dob || "—"}</dd></div>
          <div><dt>Sex</dt><dd>{p.gender || "—"}</dd></div>
        </dl>
      )}
      {grants.patientsView && <ReackPrompt patientId={row.patientId} />}
      <div className={styles.railActions}>
        {/* Rail actions close the rail first so only one dialog traps focus at a time (the page's dialog state is one slot). */}
        <RowActions row={row} grants={grants} sendingId={sendingId} h={h} />
        {grants.patientsView && row.patientMrn && <Link className={styles.linkBtn} href={`/patients?q=${encodeURIComponent(row.patientMrn)}`}>Find in patient registry</Link>}
      </div>
    </Dialog>
  );
}
