"use client";

import styles from "./nursing.module.css";
import { useState } from "react";
import { STALE } from "@nabd/api-client/queries";
import { useQueryClient } from "@tanstack/react-query";
import type { FlowStepResponse } from "@nabd/api-client/generated";
import StatusPill from "../ui/StatusPill";
import { EmptyState } from "../ui/StateViews";
import { api, ApiError } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { staleMessage } from "../../lib/queueMove";
import { detailOf, hhmm, resolveSequence, waitMinutes, type ProcedureOrder, type Row } from "./model";

// FEF-031: the Today's Procedures tab (NB-146/NB-119/NB-355) with its dialogs; own state, moved unchanged.
export default function ProceduresTab({ proceduresPendingRows, procedureOrders, canCreateOrders, canEditOrders, canAdvanceQueue, invalidateAll, setActionError }: {
  proceduresPendingRows: Row[]; procedureOrders: ProcedureOrder[]; canCreateOrders: boolean; canEditOrders: boolean; canAdvanceQueue: boolean;
  invalidateAll: () => Promise<unknown>; setActionError: (m: string | null) => void;
}) {
  const qc = useQueryClient();
  async function continueAfterProcedures(entry: Row) {
    setActionError(null);
    const steps = await qc.fetchQuery({
      queryKey: key("department-flow", { id: entry.departmentId }), staleTime: STALE.departments,
      queryFn: getter<FlowStepResponse[]>(`/departments/${entry.departmentId}/flow`),
    }).catch(() => []);
    const sequence = resolveSequence((Array.isArray(steps) ? steps : []).map((s) => ({ stepType: s.stepType ?? "" })));
    const next = sequence[sequence.indexOf(entry.status) + 1];
    if (!next) {
      setActionError("Couldn't determine the next stage.");
      return;
    }
    await api.patch(`/queue/${entry.id}/status`, { status: next, expectedStatus: entry.status })
      .catch((err) => setActionError((err instanceof ApiError && staleMessage(err.problem)) || detailOf(err, "Couldn't continue the visit.")));
    void invalidateAll();
  }

  // ── NB-146: procedure orders ─────────────────────────────────────────────
  const [showProcForm, setShowProcForm] = useState(false);
  const [procForm, setProcForm] = useState({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
  const [procFormError, setProcFormError] = useState<string | null>(null);

  async function submitProcOrder(e: React.FormEvent) {
    e.preventDefault();
    setProcFormError(null);
    try {
      await api.post("/nursing/procedure-orders", procForm);
    } catch (err) {
      setProcFormError(detailOf(err, "Couldn't create the order."));
      return;
    }
    setShowProcForm(false);
    setProcForm({ queueEntryId: "", chargeCode: "", prepNotes: "", consentNote: "" });
    void invalidateAll();
  }

  const [procRowError, setProcRowError] = useState<Record<string, string>>({});

  async function setProcedureStatus(id: string, status: string) {
    setProcRowError((prev) => { const n = { ...prev }; delete n[id]; return n; });
    await api.patch(`/nursing/procedure-orders/${id}/status`, { status })
      .catch((err) => setProcRowError((prev) => ({ ...prev, [id]: detailOf(err, "Couldn't update the procedure.") })));
    void invalidateAll();
  }

  // ── NB-119: signed consent gates starting the procedure ────────────────────
  const [consentModal, setConsentModal] = useState<{ orderId: string; signedName: string } | null>(null);
  const [consentError, setConsentError] = useState<string | null>(null);

  async function submitConsent() {
    if (!consentModal || !consentModal.signedName.trim()) return;
    setConsentError(null);
    try {
      await api.post(`/nursing/procedure-orders/${consentModal.orderId}/consent`, { signedName: consentModal.signedName });
    } catch (err) {
      // Stay open: consent that failed to record must not look recorded.
      setConsentError(detailOf(err, "Couldn't record consent."));
      return;
    }
    setConsentModal(null);
    void invalidateAll();
  }
  return (
    <>
        <>
          {proceduresPendingRows.length > 0 && (
            <div className={`${styles.card} ${styles.cardGap}`}>
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th scope="col">Token</th><th scope="col">Patient</th><th scope="col">Doctor</th><th scope="col">Wait</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
                  <tbody>
                    {proceduresPendingRows.map((r) => (
                      <tr key={r.id} className={r.priority ? styles.rowUrgent : undefined}>
                        <td data-label="Token">#{r.tokenNumber}</td>
                        <td data-label="Patient"><span className={styles.patientName}>{r.patientName}</span><span className={styles.mrn}>{r.patientMrn}</span></td>
                        <td data-label="Doctor">{r.doctorName}</td>
                        <td data-label="Wait" className={styles.muted}>{waitMinutes(r.createdAt)}m</td>
                        <td data-label="Status">
                          {r.priority && <StatusPill tone="urgent" label="Urgent" icon="alert" />}
                          <StatusPill tone="warn" label="Procedures pending" icon="clock" />
                        </td>
                        <td data-label="">{canAdvanceQueue && <button className={styles.actionBtn} onClick={() => continueAfterProcedures(r)}>Procedures done, continue</button>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {canCreateOrders && (
            <div className={`${styles.headerActions} ${styles.actionsGap}`}>
              <button className={styles.actionBtn} onClick={() => setShowProcForm(true)}>+ New procedure</button>
            </div>
          )}
          <div className={styles.card}>
            {procedureOrders.length === 0 ? (
              <EmptyState icon="list" title="No procedures scheduled today" hint="Procedure orders for today appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th scope="col">Patient</th><th scope="col">Procedure</th><th scope="col">Consent</th><th scope="col">Preparation</th><th scope="col">State</th><th scope="col"></th></tr></thead>
                  <tbody>
                    {procedureOrders.map((p) => {
                      const consented = !!p.consentSignedName;
                      const open = p.status === "ordered" || p.status === "prepped";
                      // Wireframe (Today's Procedures): an open order without signed consent reads "Consent missing" /
                      // "Blocked until consent" / Paused. The backend refuses prepped/completed until consent (ProcedureService).
                      const blocked = open && !consented;
                      return (
                        <tr key={p.id}>
                          <td data-label="Patient">{p.patientName}</td>
                          <td data-label="Procedure">{p.chargeName}</td>
                          <td data-label="Consent">
                            {consented
                              ? <span title={p.consentRecordedByName ? `Recorded by ${p.consentRecordedByName}` : undefined}>
                                  <StatusPill tone="ok" icon="check" label={`Signed · ${p.consentSignedName}${p.consentSignedAt ? ` · ${hhmm(p.consentSignedAt)}` : ""}`} />
                                </span>
                              : <div className={styles.rowActions}>
                                  <StatusPill tone="danger" label="Consent missing" icon="alert" />
                                  {canEditOrders && open && <button className={styles.smallBtn} onClick={() => { setConsentError(null); setConsentModal({ orderId: p.id, signedName: "" }); }}>Record consent</button>}
                                </div>}
                            {/* No consent-form version field exists in the API yet; the free-text note given at ordering is shown as typed. */}
                            {p.consentNote && <span className={`${styles.mrn} ${styles.muted}`}>Note: {p.consentNote}</span>}
                          </td>
                          <td data-label="Preparation">
                            {blocked
                              ? <StatusPill tone="warn" label="Blocked until consent" icon="clock" />
                              : <span className={styles.muted}>{p.prepNotes ?? "—"}</span>}
                            {blocked && p.prepNotes && <span className={`${styles.mrn} ${styles.muted}`}>{p.prepNotes}</span>}
                          </td>
                          <td data-label="State">
                            {blocked && <StatusPill tone="warn" label="Paused" icon="clock" />}
                            {p.status === "ordered" && !blocked && <StatusPill tone="neutral" label="Not started" />}
                            {p.status === "prepped" && !blocked && <StatusPill tone="info" label="In progress" icon="sync" />}
                            {p.status === "completed" && <StatusPill tone="ok" label={`Completed${p.billed ? " · billed" : " · pending bill"}`} />}
                          </td>
                          <td data-label="">
                            {open && consented && canEditOrders && (
                              <div className={styles.rowActions}>
                                {p.status === "ordered" && <button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "prepped")}>Mark prepped</button>}
                                <button className={styles.smallBtn} onClick={() => setProcedureStatus(p.id, "completed")}>Complete</button>
                              </div>
                            )}
                            {procRowError[p.id] && <div className={styles.rowError} role="alert">{procRowError[p.id]}</div>}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>

      {consentModal && canEditOrders && (
        <div className={styles.overlay} onClick={() => setConsentModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Record consent</h2>
            <div className={styles.field}>
              <label className={styles.label}>Patient&apos;s typed name (stands in for a signature)</label>
              <input className={styles.input} value={consentModal.signedName}
                onChange={(e) => setConsentModal({ ...consentModal, signedName: e.target.value })} />
            </div>
            {consentError && <div className={styles.formError} role="alert">{consentError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setConsentModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!consentModal.signedName.trim()} onClick={submitConsent}>Record consent</button>
            </div>
          </div>
        </div>
      )}

      {showProcForm && canCreateOrders && (
        <div className={styles.overlay} onClick={() => setShowProcForm(false)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitProcOrder}>
            <h2 className={styles.modalTitle}>New procedure order</h2>
            <div className={styles.field}>
              <label className={styles.label}>Queue entry ID</label>
              <input className={styles.input} value={procForm.queueEntryId} onChange={(e) => setProcForm((p) => ({ ...p, queueEntryId: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Charge code</label>
              <input className={styles.input} value={procForm.chargeCode} onChange={(e) => setProcForm((p) => ({ ...p, chargeCode: e.target.value }))} required placeholder="from Clinic Setup → Charges" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Prep notes</label>
              <textarea className={styles.textarea} value={procForm.prepNotes} onChange={(e) => setProcForm((p) => ({ ...p, prepNotes: e.target.value }))} placeholder="Chair 1 ready" />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Consent note</label>
              <input className={styles.input} value={procForm.consentNote} onChange={(e) => setProcForm((p) => ({ ...p, consentNote: e.target.value }))} placeholder="Consent form name and version (optional)" />
            </div>
            {procFormError && <div className={styles.formError} role="alert">{procFormError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowProcForm(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}>Create order</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
