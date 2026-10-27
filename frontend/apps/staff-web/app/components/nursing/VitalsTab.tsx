"use client";

import styles from "./nursing.module.css";
import Icon from "../ui/Icon";
import StatusPill from "../ui/StatusPill";
import { EmptyState } from "../ui/StateViews";
import { VISIT_LABEL } from "@nabd/domain";
import { FLAG_ICON, ageSex, flagsFor, hhmm, statusLabel, type Row } from "./model";

// FEF-031: the Arrivals & Vitals tab (worklist table + summary side cards), moved unchanged from the old page.
export interface VitalsTabProps {
  visibleVitalsRows: Row[]; queueForbidden: boolean; noQueueAccess: React.ReactNode; vitalsEmpty: { title: string; hint: string };
  rowActionable: (r: Row) => boolean; openRow: (r: Row) => void; openVitalsModal: (id: string) => void; openViewVitals: (r: Row) => void;
  canCaptureVitals: boolean; canViewVitals: boolean; canCreateOrders: boolean; canFlagPriority: boolean;
  recordedCount: number; outOfRangeCount: number;
  onCapture: () => void; onRecordVaccine: () => void; onMarkUrgent: () => void; onOpenVaccines: () => void;
}
export default function VitalsTab({ visibleVitalsRows, queueForbidden, noQueueAccess, vitalsEmpty, rowActionable, openRow, openVitalsModal, openViewVitals,
  canCaptureVitals, canViewVitals, canCreateOrders, canFlagPriority, recordedCount, outOfRangeCount, onCapture, onRecordVaccine, onMarkUrgent, onOpenVaccines }: VitalsTabProps) {
  return (
        <>
          <div className={`${styles.headerActions} ${styles.actionsGap}`}>
            {canCaptureVitals && !queueForbidden && <button className={styles.actionBtn} onClick={() => onCapture()}><Icon name="pulse" size={14} />Capture vitals</button>}
            {canCreateOrders && <button className={styles.secondaryBtn} onClick={() => onRecordVaccine()}><Icon name="syringe" size={14} />Record vaccine</button>}
            {canFlagPriority && !queueForbidden && <button className={styles.secondaryBtn} onClick={() => onMarkUrgent()}><Icon name="bolt" size={14} />Mark urgent</button>}
          </div>

          <div className={styles.layout}>
            <div className={styles.card}>
              {queueForbidden ? noQueueAccess : visibleVitalsRows.length === 0 ? (
                <EmptyState icon="pulse" title={vitalsEmpty.title} hint={vitalsEmpty.hint} />
              ) : (
                <div className={styles.tableWrap}>
                  <table className={styles.table}>
                    <thead><tr><th scope="col">Patient</th><th scope="col">Visit</th><th scope="col">Clinical flags</th><th scope="col">Last vitals</th><th scope="col">Status</th><th scope="col"><span className={styles.srOnly}>Action</span></th></tr></thead>
                    <tbody>
                      {visibleVitalsRows.map((r) => {
                        const flags = flagsFor(r);
                        const hasAllergy = flags.some((f) => f.kind === "danger");
                        const rowClass = hasAllergy ? styles.rowFlagged : r.priority ? styles.rowUrgent : undefined;
                        return (
                          <tr key={r.id} className={`${rowClass ?? ""} ${rowActionable(r) ? styles.rowClickable : ""}`}
                            {...(rowActionable(r) ? { tabIndex: 0, onClick: () => openRow(r), onKeyDown: (e: React.KeyboardEvent) => { if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); openRow(r); } } } : {})}>
                            <td data-label="Patient" className={styles.patientCell} title={r.patientMrn ? `MRN ${r.patientMrn}` : undefined}>
                              <div><span className={styles.patientName}>{r.patientName}</span>
                              <span className={styles.mrn} title={r.doctorName !== "—" ? r.doctorName : undefined}>{[ageSex(r.dob, r.gender, r.ageYears), `T-${r.tokenNumber}`, r.doctorName !== "—" ? r.doctorName : "", `${r.wait}m wait`].filter(Boolean).join(" · ")}</span></div>
                            </td>
                            <td data-label="Visit">
                              <span className={styles.visitCell}>
                                <Icon name={r.hasAppointment ? "calendar" : r.source === "walk_in" ? "login" : "users"} size={15} />
                                {r.hasAppointment ? "Appointment" : VISIT_LABEL[r.source] ?? r.source}
                              </span>
                            </td>
                            <td data-label="Clinical flags" className={styles.flagCell}>
                              {flags.length === 0 && !r.priority && !r.flagsUnknown ? <span className={styles.muted}>No flags</span> : (
                                <div className={styles.flagList}>
                                  {r.flagsUnknown && <StatusPill tone="warn" label="Allergies unknown: record unavailable" icon="alert" />}
                                  {r.priority && <StatusPill tone="urgent" label={r.priorityReason ? `Urgent: ${r.priorityReason}` : "Urgent"} icon="alert" />}
                                  {flags.map((f, i) => <StatusPill key={i} tone={f.kind} label={f.text} icon={FLAG_ICON[f.kind]} />)}
                                </div>
                              )}
                            </td>
                            <td data-label="Last vitals" className={styles.muted}>{r.lastVitalsAt ? hhmm(r.lastVitalsAt) : r.status !== "vitals_pending" && canViewVitals ? "Not recorded" : "—"}</td>
                            <td data-label="Status">
                              {r.status === "vitals_pending"
                                ? <StatusPill tone="warn" label="Waiting" icon="clock" />
                                : <StatusPill tone={r.vitalsOutOfRange ? "danger" : "ok"} label={r.vitalsOutOfRange ? `${statusLabel(r.status)} · out of range` : statusLabel(r.status)} icon={r.vitalsOutOfRange ? "alert" : "check"} />}
                            </td>
                            <td data-label="" className={styles.actionCell}>{r.status === "vitals_pending" && canCaptureVitals && <button className={styles.actionBtn} onClick={(e) => { e.stopPropagation(); openVitalsModal(r.id); }}><Icon name="pulse" size={14} />Capture vitals</button>}
                              {r.status !== "vitals_pending" && canViewVitals && r.lastVitalsAt && <button className={styles.actionBtn} onClick={(e) => { e.stopPropagation(); openViewVitals(r); }}><Icon name="pulse" size={14} />View vitals</button>}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className={styles.sidebarStack}>
              {!queueForbidden && <div className={styles.card}>
                <div className={styles.sideTitle}><Icon name="pulse" size={14} />Vitals summary</div>
                <div className={styles.summaryRow}><span>Recorded today</span><span className={styles.summaryValue}>{recordedCount}</span></div>
                <div className={styles.summaryRow}>
                  <span>Out of range</span>
                  <span className={outOfRangeCount > 0 ? styles.summaryDanger : styles.summaryValue}>
                    {outOfRangeCount > 0 && <Icon name="alert" size={14} />} {outOfRangeCount}
                  </span>
                </div>
                {/* No real source for capture duration yet — an honest dash, never an invented figure. */}
                <div className={styles.summaryRow}><span>Avg capture time</span><span className={styles.summaryValue} title="Not measured yet">—</span></div>
              </div>}

              {/* No backend exists for vaccines/injections yet (filed separately) — shown as an
                  honest "not available" card rather than fabricated due-dates. */}
              <div className={styles.card}>
                <div className={styles.sideTitle}><Icon name="syringe" size={14} />Vaccines &amp; injections due</div>
                <div className={styles.sideState}>Not available yet. No immunisation schedule is set up.</div>
                <div className={styles.sideFooter}>
                  <button type="button" className={styles.secondaryBtnWide} onClick={() => onOpenVaccines()}>Open vaccine log</button>
                </div>
              </div>
            </div>
          </div>
        </>
  );
}
