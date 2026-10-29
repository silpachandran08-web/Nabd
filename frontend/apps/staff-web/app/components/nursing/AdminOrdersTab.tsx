"use client";

import styles from "./nursing.module.css";
import { useState } from "react";
import type { StaffRosterEntry } from "@nabd/api-client/generated";
import StatusPill from "../ui/StatusPill";
import { EmptyState } from "../ui/StateViews";
import { api } from "../../lib/api";
import { detailOf, type AdministrationOrder } from "./model";

// FEF-031: the Administration Orders tab (NB-145) with its three dialogs; own state, moved unchanged.
export default function AdminOrdersTab({ administrationOrders, staff, canCreateOrders, canEditOrders, invalidateAll, setActionError }: {
  administrationOrders: AdministrationOrder[]; staff: (StaffRosterEntry & { id: string; name: string })[]; canCreateOrders: boolean; canEditOrders: boolean;
  invalidateAll: () => Promise<unknown>; setActionError: (m: string | null) => void;
}) {
  const [showAdminForm, setShowAdminForm] = useState(false);
  const [adminForm, setAdminForm] = useState({ queueEntryId: "", drugName: "", dose: "", route: "IM", site: "" });
  const [adminFormError, setAdminFormError] = useState<string | null>(null);
  const [witnessModal, setWitnessModal] = useState<{ orderId: string; witnessId: string } | null>(null);
  const [refuseModal, setRefuseModal] = useState<{ orderId: string; reason: string } | null>(null);

  async function submitAdminOrder(e: React.FormEvent) {
    e.preventDefault();
    setAdminFormError(null);
    try {
      await api.post("/nursing/administration-orders", adminForm);
    } catch (err) {
      setAdminFormError(detailOf(err, "Couldn't create the order."));
      return;
    }
    setShowAdminForm(false);
    setAdminForm({ queueEntryId: "", drugName: "", dose: "", route: "IM", site: "" });
    void invalidateAll();
  }

  async function submitAdminister() {
    if (!witnessModal) return;
    setActionError(null);
    await api.post(`/nursing/administration-orders/${witnessModal.orderId}/administer`, { witnessedByStaffId: witnessModal.witnessId })
      .catch((err) => setActionError(detailOf(err, "Couldn't record administration.")));
    setWitnessModal(null);
    void invalidateAll();
  }

  async function submitRefuse() {
    if (!refuseModal) return;
    setActionError(null);
    await api.post(`/nursing/administration-orders/${refuseModal.orderId}/refuse`, { reason: refuseModal.reason })
      .catch((err) => setActionError(detailOf(err, "Couldn't record refusal.")));
    setRefuseModal(null);
    void invalidateAll();
  }
  return (
    <>
        <>
          {canCreateOrders && (
            <div className={`${styles.headerActions} ${styles.actionsGap}`}>
              <button className={styles.actionBtn} onClick={() => setShowAdminForm(true)}>+ New order</button>
            </div>
          )}
          <div className={styles.card}>
            {administrationOrders.length === 0 ? (
              <EmptyState icon="syringe" title="No administration orders today" hint="Orders placed by doctors for administration appear here." />
            ) : (
              <div className={styles.tableWrap}>
                <table className={styles.table}>
                  <thead><tr><th scope="col">Patient</th><th scope="col">Ordered item</th><th scope="col">Ordered by</th><th scope="col">Route &amp; site</th><th scope="col">State</th><th scope="col"></th></tr></thead>
                  <tbody>
                    {administrationOrders.map((o) => (
                      <tr key={o.id}>
                        <td data-label="Patient">{o.patientName}</td>
                        <td data-label="Ordered item">{o.drugName} · {o.dose}</td>
                        <td data-label="Ordered by">{o.orderedByName}</td>
                        <td data-label="Route & site">{o.route}{o.site ? ` · ${o.site}` : ""}</td>
                        <td data-label="State">
                          {o.status === "not_started" && <StatusPill tone="neutral" label="Not started" />}
                          {o.status === "administered" && <StatusPill tone="ok" label={`Administered · witness ${o.witnessedByName}`} />}
                          {o.status === "refused" && <StatusPill tone="danger" label={`Refused: ${o.refuseReason}`} icon="x" />}
                        </td>
                        <td data-label="">
                          {o.status === "not_started" && canEditOrders && (
                            <div className={styles.rowActions}>
                              <button className={styles.smallBtn} onClick={() => setWitnessModal({ orderId: o.id, witnessId: "" })}>Administer</button>
                              <button className={styles.smallBtn} onClick={() => setRefuseModal({ orderId: o.id, reason: "" })}>Refuse</button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>

      {showAdminForm && canCreateOrders && (
        <div className={styles.overlay} onClick={() => setShowAdminForm(false)}>
          <form className={styles.modal} onClick={(e) => e.stopPropagation()} onSubmit={submitAdminOrder}>
            <h2 className={styles.modalTitle}>New administration order</h2>
            <div className={styles.field}>
              <label className={styles.label}>Queue entry ID</label>
              <input className={styles.input} value={adminForm.queueEntryId} onChange={(e) => setAdminForm((p) => ({ ...p, queueEntryId: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Drug</label>
              <input className={styles.input} value={adminForm.drugName} onChange={(e) => setAdminForm((p) => ({ ...p, drugName: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Dose</label>
              <input className={styles.input} value={adminForm.dose} onChange={(e) => setAdminForm((p) => ({ ...p, dose: e.target.value }))} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Route</label>
              <select className={styles.select} value={adminForm.route} onChange={(e) => setAdminForm((p) => ({ ...p, route: e.target.value }))}>
                {["IM", "IV", "SC", "infusion", "oral", "topical"].map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <div className={styles.field}>
              <label className={styles.label}>Site (optional)</label>
              <input className={styles.input} value={adminForm.site} onChange={(e) => setAdminForm((p) => ({ ...p, site: e.target.value }))} placeholder="left deltoid" />
            </div>
            {adminFormError && <div className={styles.formError} role="alert">{adminFormError}</div>}
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setShowAdminForm(false)}>Cancel</button>
              <button type="submit" className={styles.submitBtn}>Create order</button>
            </div>
          </form>
        </div>
      )}

      {witnessModal && canEditOrders && (
        <div className={styles.overlay} onClick={() => setWitnessModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Confirm the five rights, then witness</h2>
            <p className={`${styles.muted} ${styles.modalNote}`}>Right patient, right drug, right dose, right route, right time — confirmed before administering.</p>
            <div className={styles.field}>
              <label className={styles.label}>Witnessed by</label>
              <select className={styles.select} value={witnessModal.witnessId} onChange={(e) => setWitnessModal({ ...witnessModal, witnessId: e.target.value })}>
                <option value="">Select a witness…</option>
                {staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setWitnessModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!witnessModal.witnessId} onClick={submitAdminister}>Administer</button>
            </div>
          </div>
        </div>
      )}

      {refuseModal && canEditOrders && (
        <div className={styles.overlay} onClick={() => setRefuseModal(null)}>
          <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
            <h2 className={styles.modalTitle}>Refuse administration</h2>
            <div className={styles.field}>
              <label className={styles.label}>Reason</label>
              <textarea className={styles.textarea} value={refuseModal.reason} onChange={(e) => setRefuseModal({ ...refuseModal, reason: e.target.value })} />
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.cancelBtn} onClick={() => setRefuseModal(null)}>Cancel</button>
              <button type="button" className={styles.submitBtn} disabled={!refuseModal.reason.trim()} onClick={submitRefuse}>Record refusal</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
