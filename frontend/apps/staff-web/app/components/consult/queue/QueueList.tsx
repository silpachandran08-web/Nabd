"use client";

// FEF-030: the queue list screen (title, scope and status filters, table with its loading / forbidden / error / empty states).
import Link from "next/link";
import { VISIT_LABEL } from "@nabd/domain";
import StatusPill from "../../ui/StatusPill";
import { EmptyState, ErrorState, Skeleton } from "../../ui/StateViews";
import { age, DONE_STATUSES, minutesSince, WAITING_STATUSES, type Row } from "../shared";
import StatusBadge from "./StatusBadge";
import type { ConsultQueue } from "./useConsultQueue";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

function rowArrows(e: React.KeyboardEvent<HTMLTableRowElement>) {
  if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
  const sib = (e.key === "ArrowDown" ? e.currentTarget.nextElementSibling : e.currentTarget.previousElementSibling) as HTMLElement | null;
  const btn = sib?.querySelector("button");
  if (btn) { e.preventDefault(); btn.focus(); }
}

export default function QueueList({ q, onOpen }: { q: ConsultQueue; onOpen: (r: Row) => void }) {
  const { rows, loading, error, forbidden, scope, statusFilter, selfId, now } = q;
  const visible = rows.filter((r) =>
    statusFilter === "all" ? true
    : statusFilter === "waiting" ? WAITING_STATUSES.includes(r.status)
    : statusFilter === "in_consult" ? r.status === "in_consult"
    : DONE_STATUSES.includes(r.status));

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Consultation Workspace</h1>
        <p className={styles.subtitle}>Today&apos;s queue.</p>
      </div>

      <div className={styles.toolbar}>
        <div className={styles.seg} role="group" aria-label="Queue scope">
          {([["mine", "My patients"], ["all", "All clinic"]] as const).map(([id, label]) => (
            <button key={id} type="button" className={`${styles.segBtn} ${scope === id ? styles.segBtnOn : ""}`} aria-pressed={scope === id} onClick={() => q.changeScope(id)}>{label}</button>
          ))}
        </div>
        <div className={styles.seg} role="group" aria-label="Status filter">
          {([["all", "All"], ["waiting", "Waiting"], ["in_consult", "In consult"], ["done", "Done"]] as const).map(([id, label]) => (
            <button key={id} type="button" className={`${styles.segBtn} ${statusFilter === id ? styles.segBtnOn : ""}`} aria-pressed={statusFilter === id} onClick={() => q.setStatusFilter(id)}>{label}</button>
          ))}
        </div>
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={p.skelPad}><Skeleton rows={5} label="Loading today's queue" /></div>
        ) : forbidden ? (
          <EmptyState icon="shield" title="No access to the queue" hint="Your role doesn't include the consultation queue." />
        ) : error ? (
          <ErrorState message={error} onRetry={() => void q.load(scope)} />
        ) : rows.length === 0 ? (
          <EmptyState title="Nothing here today" hint={scope === "mine" ? "No patients are assigned to you in today's queue. Switch to All clinic to see everyone." : "No patients are in today's queue."}
            action={<Link href="/patients" className={`${styles.actionBtnGhost} ${styles.anchorBtn}`}>Open patients</Link>} />
        ) : visible.length === 0 ? (
          <EmptyState title="No patients match this filter" hint="Choose another status to see the rest of the queue." />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th scope="col">Patient</th><th scope="col" className={styles.hideNarrow}>Visit type</th><th scope="col">Clinical flags</th><th scope="col" className={styles.hideNarrow}>Wait · arrival</th><th scope="col">Status</th><th scope="col"><span className={p.srOnly}>Action</span></th></tr>
              </thead>
              <tbody>
                {visible.map((r) => {
                  const waiting = WAITING_STATUSES.includes(r.status);
                  const mins = waiting && now ? minutesSince(r.createdAt, now) : null;
                  const mine = r.doctorId === selfId;
                  const pt = r.patient;
                  return (
                    <tr key={r.id} onMouseEnter={() => q.warm(r)} onFocus={() => q.warm(r)} onKeyDown={rowArrows}>
                      <td className={styles.token}>
                        #{r.tokenNumber} {r.patientName}
                        <span className={styles.subline}>{[pt ? `${age(pt.dob)} y` : null, r.presentingComplaint?.trim() || null].filter(Boolean).join(" · ") || "No reason recorded"}</span>
                      </td>
                      <td className={styles.hideNarrow}>{r.appointmentId ? "Appointment" : (VISIT_LABEL[r.source ?? ""] ?? "Walk-in")}</td>
                      <td>
                        <div className={styles.flags}>
                          {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? `Urgent: ${r.priorityReason}` : "Urgent"} />}
                          {pt && pt.allergies.length > 0 && <StatusPill tone="danger" label="Allergy" />}
                          {pt && pt.chronicConditions.length > 0 && <StatusPill tone="warn" icon="pulse" label="Chronic" />}
                          {pt && pt.activePackages > 0 && <StatusPill tone="info" icon="package" label="Package" />}
                          {!pt && <StatusPill tone="warn" label="Flags unavailable" />}
                        </div>
                      </td>
                      <td className={styles.hideNarrow}>
                        {waiting && mins !== null ? `${mins} min` : "—"}{r.createdAt ? <span className={styles.subline}>Arrived {new Date(r.createdAt).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}</span> : null}
                      </td>
                      <td><StatusBadge status={r.status} /></td>
                      <td>
                        {r.status === "in_consult" && mine
                          ? <button className={styles.actionBtn} onClick={() => onOpen(r)}>Resume</button>
                          : q.canStartRow(r)
                            ? <button className={styles.actionBtn} onClick={() => onOpen(r)}>Start consultation</button>
                            : <button className={styles.actionBtnGhost} onClick={() => onOpen(r)}>Summary</button>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </main>
  );
}
