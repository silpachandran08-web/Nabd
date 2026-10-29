"use client";

// FEF-030: the queue list screen (title, scope and status filters, table with its loading / forbidden / error / empty states).
import { useTranslations } from "next-intl";
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
  const tx = useTranslations("consult.queueList");
  const g = useTranslations("glossary");
  const { rows, loading, error, forbidden, scope, statusFilter, selfId, now } = q;
  const visible = rows.filter((r) =>
    statusFilter === "all" ? true
    : statusFilter === "waiting" ? WAITING_STATUSES.includes(r.status)
    : statusFilter === "in_consult" ? r.status === "in_consult"
    : DONE_STATUSES.includes(r.status));

  return (
    <main className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>{tx("consultationWorkspace")}</h1>
        <p className={styles.subtitle}>{tx("todaySQueue")}</p>
      </div>

      <div className={styles.toolbar}>
        <div className={styles.seg} role="group" aria-label={tx("queueScope")}>
          {(["mine", "all"] as const).map((id) => (
            <button key={id} type="button" className={`${styles.segBtn} ${scope === id ? styles.segBtnOn : ""}`} aria-pressed={scope === id} onClick={() => q.changeScope(id)}>{tx(`scope.${id}`)}</button>
          ))}
        </div>
        <div className={styles.seg} role="group" aria-label={tx("statusFilter")}>
          {(["all", "waiting", "in_consult", "done"] as const).map((id) => (
            <button key={id} type="button" className={`${styles.segBtn} ${statusFilter === id ? styles.segBtnOn : ""}`} aria-pressed={statusFilter === id} onClick={() => q.setStatusFilter(id)}>{tx(`filter.${id}`)}</button>
          ))}
        </div>
      </div>

      <div className={styles.card}>
        {loading ? (
          <div className={p.skelPad}><Skeleton rows={5} label={tx("loadingTodaySQueue")} /></div>
        ) : forbidden ? (
          <EmptyState icon="shield" title={tx("noAccessToTheQueue")} hint={tx("yourRoleDoesnTInclude")} />
        ) : error ? (
          <ErrorState message={error} onRetry={() => void q.load(scope)} />
        ) : rows.length === 0 ? (
          <EmptyState title={tx("nothingHereToday")} hint={scope === "mine" ? tx("emptyMine") : tx("emptyAll")}
            action={<Link href="/patients" className={`${styles.actionBtnGhost} ${styles.anchorBtn}`}>{tx("openPatients")}</Link>} />
        ) : visible.length === 0 ? (
          <EmptyState title={tx("noPatientsMatchThisFilter")} hint={tx("chooseAnotherStatusToSee")} />
        ) : (
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <thead>
                <tr><th scope="col">{tx("patient")}</th><th scope="col" className={styles.hideNarrow}>{tx("visitType")}</th><th scope="col">{tx("clinicalFlags")}</th><th scope="col" className={styles.hideNarrow}>{tx("waitArrival")}</th><th scope="col">{tx("status")}</th><th scope="col"><span className={p.srOnly}>{tx("action")}</span></th></tr>
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
                        <span className={styles.subline}>{[pt ? tx("ageYears", { age: age(pt.dob) }) : null, r.presentingComplaint?.trim() || null].filter(Boolean).join(" · ") || tx("noReasonRecorded")}</span>
                      </td>
                      <td className={styles.hideNarrow}>{r.appointmentId ? tx("appointment") : (r.source && VISIT_LABEL[r.source] ? tx(`source.${r.source}`) : tx("source.walk_in"))}</td>
                      <td>
                        <div className={styles.flags}>
                          {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? tx("urgentReason", { reason: r.priorityReason }) : tx("urgent")} />}
                          {pt && pt.allergies.length > 0 && <StatusPill tone="danger" label={g("allergy.label")} />}
                          {pt && pt.chronicConditions.length > 0 && <StatusPill tone="warn" icon="pulse" label={g("condition.chronic")} />}
                          {pt && pt.activePackages > 0 && <StatusPill tone="info" icon="package" label={tx("package")} />}
                          {!pt && <StatusPill tone="warn" label={tx("flagsUnavailable")} />}
                        </div>
                      </td>
                      <td className={styles.hideNarrow}>
                        {waiting && mins !== null ? tx("minutes", { mins }) : "—"}{r.createdAt ? <span className={styles.subline}>{tx("arrived")}{" "}{new Date(r.createdAt).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" })}</span> : null}
                      </td>
                      <td><StatusBadge status={r.status} /></td>
                      <td>
                        {r.status === "in_consult" && mine
                          ? <button className={styles.actionBtn} onClick={() => onOpen(r)}>{tx("resume")}</button>
                          : q.canStartRow(r)
                            ? <button className={styles.actionBtn} onClick={() => onOpen(r)}>{tx("startConsultation")}</button>
                            : <button className={styles.actionBtnGhost} onClick={() => onOpen(r)}>{tx("summary")}</button>}
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
