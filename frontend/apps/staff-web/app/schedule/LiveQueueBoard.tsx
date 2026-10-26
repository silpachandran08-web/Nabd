"use client";

import { useQuery } from "@tanstack/react-query";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { usePolling } from "../lib/usePolling";
import { useLiveRefresh } from "../lib/live";
import { ApiError } from "../lib/api";
import { queueQuery, useReload } from "../lib/query";
import type { QueueEntry } from "@nabd/domain";
import styles from "./schedule.module.css";

type BoardDoctor = { id: string; name: string; department: string | null; onLeave: boolean };

const POLL_MS = 15_000;
const STATUS: Record<string, { label: string; tone: PillTone }> = {
  checked_in: { label: "Checked in", tone: "warn" },
  waiting: { label: "Waiting", tone: "warn" },
  billing_pending: { label: "Billing pending", tone: "info" },
  vitals_pending: { label: "Vitals pending", tone: "info" },
  vitals_done: { label: "Vitals done", tone: "ok" },
  in_consult: { label: "In consultation", tone: "sync" },
  procedures_pending: { label: "Procedures pending", tone: "sync" },
  checkout_pending: { label: "Checkout pending", tone: "info" },
  completed: { label: "Completed", tone: "ok" },
  no_show: { label: "No-show", tone: "danger" },
};
const FINISHED = new Set(["completed", "no_show"]);

function statusOf(status: string): { label: string; tone: PillTone } {
  return STATUS[status] ?? { label: status.replace(/_/g, " "), tone: "neutral" };
}

/**
 * Live Queue: one column per doctor (wireframe per-doctor board), built by grouping GET /v1/queue
 * by doctorId. Patient names come from the schedule day's bookings (matched on queueEntryId);
 * an entry the schedule doesn't know is shown by token only — Arrivals has the full patient list.
 */
export default function LiveQueueBoard({ doctors, names }: {
  doctors: BoardDoctor[];
  names: Record<string, string>;
}) {
  // The same GET /queue (and key) as Arrivals and the sidebar badge; a reload cancels a slower one in flight (FEF-004).
  const q = useQuery(queueQuery());
  const entries = (Array.isArray(q.data) ? q.data : null) as QueueEntry[] | null;
  const error = !q.error ? null
    : q.error instanceof ApiError ? (q.error.status === 403 ? "You don't have access to the live queue." : "Couldn't load the live queue.")
    : "Couldn't reach the server. Check your connection and try again.";
  const load = useReload("queue");
  usePolling(load, POLL_MS);
  useLiveRefresh(["queue"], load);

  if (error && !entries) return <ErrorState message={error} onRetry={() => void load()} />;
  if (!entries) return <Skeleton rows={5} label="Loading live queue" />;
  if (doctors.length === 0 && entries.length === 0) {
    return <EmptyState title="Nobody in the queue" hint="Patients appear here once they are checked in." />;
  }

  const known = new Set(doctors.map((d) => d.id));
  const columns: (BoardDoctor & { orphan?: boolean })[] = [
    ...doctors,
    ...(entries.some((e) => !known.has(e.doctorId)) ? [{ id: "__other", name: "Other doctors", department: null, onLeave: false, orphan: true }] : []),
  ];

  return (
    <>
      {error && <ErrorState message={error} onRetry={() => void load()} />}
      <div className={styles.board}>
        {columns.map((col) => {
          const mine = entries.filter((e) => (col.orphan ? !known.has(e.doctorId) : e.doctorId === col.id));
          const active = mine.filter((e) => !FINISHED.has(e.status));
          const done = mine.length - active.length;
          const waiting = active.filter((e) => e.status !== "in_consult" && e.status !== "procedures_pending").length;
          return (
            <section key={col.id} className={styles.boardCol} aria-label={`${col.name} queue`}>
              <header className={styles.boardHead}>
                <span className={styles.boardName}>{col.name}</span>
                {col.onLeave && <StatusPill tone="neutral" label="On leave" icon="clock" />}
                <span className={styles.boardMeta}>{waiting} waiting · {active.length - waiting} in consultation{done > 0 && ` · ${done} done`}</span>
              </header>
              {active.length === 0 ? (
                <p className={styles.boardEmpty}>No patients in the queue.</p>
              ) : (
                <ol className={styles.boardList}>
                  {active.map((e) => {
                    const s = statusOf(e.status);
                    return (
                      <li key={e.id} className={styles.boardCard}>
                        <span className={styles.boardToken}>T-{e.tokenNumber}</span>
                        <span className={styles.boardWho}>
                          <span className={styles.slotName}>{names[e.id] ?? "Patient"}</span>
                          <span className={styles.boardPills}>
                            <StatusPill tone={s.tone} label={s.label} />
                            {e.priority && <StatusPill tone="urgent" label={e.priorityReason ? `Priority · ${e.priorityReason}` : "Priority"} />}
                          </span>
                        </span>
                      </li>
                    );
                  })}
                </ol>
              )}
            </section>
          );
        })}
      </div>
    </>
  );
}
