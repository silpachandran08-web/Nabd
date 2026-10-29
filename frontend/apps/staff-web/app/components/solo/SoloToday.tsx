"use client";

import { useRouter } from "next/navigation";
import { DONE_STATUSES } from "@nabd/domain";
import styles from "./solo.module.css";
import StatusPill from "../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { useHasMounted } from "../../lib/useHasMounted";
import { nextWaiting, useCallNext } from "./callNext";
import { useSoloQueue, type SoloRow } from "./useSoloQueue";

const STAGE: Record<string, string> = { checked_in: "Arrived", waiting: "Waiting", vitals_pending: "Vitals", vitals_done: "Ready", in_consult: "In consult", procedures_pending: "Procedures" };
const waitMin = (iso: string) => Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));

// CLN-008 solo.today: the doctor's queue on a phone. Call next and Start sit in the bottom bar (thumb zone).
export default function SoloToday() {
  const router = useRouter();
  const mounted = useHasMounted();
  const { rows, loading, error, refetch } = useSoloQueue();
  const open = rows.filter((r) => !DONE_STATUSES.includes(r.status) && r.status !== "transferred_out");
  const inRoom = open.find((r) => r.status === "in_consult") ?? null;
  const next = nextWaiting(open);
  const call = useCallNext(next, refetch);
  const canMove = mounted && can("queue:edit");
  const canConsult = mounted && can("clinical:view");
  return (
    <main className={styles.page}>
      <header className={styles.header}>
        <div>
          <h1 className={styles.title}>Today</h1>
          <p className={styles.subtitle}>{loading ? "" : `${open.length} in your queue`}</p>
        </div>
      </header>

      {call.stale && (
        <div className={styles.notice} role="alert">
          <span>{call.stale}</span>
          <button type="button" className={styles.noticeBtn} onClick={() => { call.clear(); void refetch(); }}>Refresh</button>
        </div>
      )}
      {call.error && <div className={styles.notice} role="alert">{call.error}</div>}

      {loading ? <Skeleton rows={5} label="Loading your queue" />
        : error ? <ErrorState message={error} onRetry={() => void refetch()} />
        : open.length === 0 ? <section className={styles.card}><EmptyState icon="users" title="No patients in your queue." hint="Patients appear here once they are checked in to you." /></section>
        : (
          <section className={styles.card} aria-label="Queue">
            <ul className={styles.list}>
              {open.map((r) => <QueueRow key={r.id} r={r} active={r.id === inRoom?.id} onOpen={canConsult ? () => router.push(`/solo/consult?entry=${encodeURIComponent(r.id)}`) : undefined} />)}
            </ul>
          </section>
        )}

      {(canMove || inRoom) && (
        <nav className={styles.bar} aria-label="Queue actions">
          {canMove && <button type="button" className={`${styles.barBtn} ${styles.barPrimary}`} disabled={!next || call.busy || !!inRoom} onClick={() => void call.callNext()}>
            {call.busy ? "Calling…" : "Call next"}
          </button>}
          {inRoom && canConsult && <button type="button" className={styles.barBtn} onClick={() => router.push(`/solo/consult?entry=${encodeURIComponent(inRoom.id)}`)}>Start</button>}
        </nav>
      )}
    </main>
  );
}

function QueueRow({ r, active, onOpen }: { r: SoloRow; active: boolean; onOpen?: () => void }) {
  return (
    <li className={`${styles.row} ${active ? styles.rowActive : ""}`}>
      <span className={styles.token}>#{r.tokenNumber}</span>
      <span className={styles.name}>{onOpen ? <button type="button" className={styles.nameBtn} onClick={onOpen}>{r.patientName ?? `Token ${r.tokenNumber}`}</button> : (r.patientName ?? `Token ${r.tokenNumber}`)}</span>
      <span className={styles.chips}>
        {r.priority && <StatusPill tone="urgent" label="Urgent" icon="alert" />}
        <StatusPill tone={r.status === "in_consult" ? "info" : "warn"} label={`${STAGE[r.status] ?? r.status.replace(/_/g, " ")}${r.status === "in_consult" ? "" : ` · ${waitMin(r.createdAt)}m`}`} icon={r.status === "in_consult" ? "users" : "clock"} />
      </span>
    </li>
  );
}
