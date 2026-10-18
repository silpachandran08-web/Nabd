"use client";

import { useCallback, useEffect, useState } from "react";
import StatusPill, { type PillTone } from "../components/ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../components/ui/StateViews";
import { usePolling } from "../lib/usePolling";
import styles from "./schedule.module.css";

// Matches GET /v1/queue (QueueController) — same shape the Arrivals page reads.
type QueueEntry = {
  id: string; appointmentId: string | null; patientId: string; doctorId: string; departmentId: string;
  queueDate: string; tokenNumber: number; status: string; priority: boolean; priorityReason: string | null;
  createdAt: string; source: string;
};
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
export default function LiveQueueBoard({ doctors, names, authedFetch }: {
  doctors: BoardDoctor[];
  names: Record<string, string>;
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
}) {
  const [entries, setEntries] = useState<QueueEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await authedFetch("/queue");
      if (!res) return;
      if (!res.ok) {
        setError(res.status === 403 ? "You don't have access to the live queue." : "Couldn't load the live queue.");
        return;
      }
      setEntries(await res.json());
      setError(null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);
  usePolling(load, POLL_MS);

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
