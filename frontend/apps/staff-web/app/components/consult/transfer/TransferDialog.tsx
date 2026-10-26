"use client";

import Dialog from "../../ui/Dialog";
import { Skeleton } from "../../ui/StateViews";
import type { Transfer } from "./useTransfer";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

export default function TransferDialog({ t }: { t: Transfer }) {
  if (!t.showTransfer) return null;
  return (
    <Dialog title="Transfer to another department" onClose={() => t.setShowTransfer(false)}>
      {t.transferTargets === null ? (
        <Skeleton rows={2} label="Loading transfer options" />
      ) : t.transferTargets.length === 0 ? (
        <p className={p.muted}>This department isn&apos;t configured to transfer patients anywhere.</p>
      ) : (
        <>
          <div className={styles.formRow}>
            <label className={styles.fieldLabel} htmlFor="trDept">Department</label>
            <select id="trDept" className={styles.select} value={t.transferDepartmentId} onChange={(e) => { t.setTransferDepartmentId(e.target.value); t.setTransferDoctorId(""); }}>
              <option value="">Select a department…</option>
              {t.transferTargets.map((x) => <option key={x.departmentId} value={x.departmentId}>{x.departmentName}</option>)}
            </select>
          </div>
          {t.transferDepartmentId && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trDoc">Doctor</label>
              <select id="trDoc" className={styles.select} value={t.transferDoctorId} onChange={(e) => t.setTransferDoctorId(e.target.value)}>
                <option value="">Select a doctor…</option>
                {t.transferTargets.find((x) => x.departmentId === t.transferDepartmentId)?.doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </div>
          )}
          {t.transferDoctorId && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trReason">Reason</label>
              <select id="trReason" className={styles.select} value={t.transferReason} onChange={(e) => t.setTransferReason(e.target.value)}>
                <option value="">Select a reason…</option>
                <option value="wrong_department">Wrong department</option>
                <option value="doctor_unavailable">Doctor unavailable</option>
                <option value="patient_request">Patient request</option>
                <option value="other">Other</option>
              </select>
            </div>
          )}
          {t.transferReason === "other" && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trNote">Note</label>
              <input id="trNote" className={styles.select} maxLength={200} value={t.transferNote} onChange={(e) => t.setTransferNote(e.target.value)} />
            </div>
          )}
        </>
      )}
      {t.transferError && <div className={`${p.notice} ${p.noticeDanger}`} role="alert">{t.transferError}</div>}
      <div className={p.dialogActions}>
        <button type="button" className={p.btn} onClick={() => t.setShowTransfer(false)}>Cancel</button>
        <button type="button" className={p.btnPrimary} onClick={t.submitTransfer} disabled={t.transferring || !t.transferDepartmentId || !t.transferDoctorId || !t.transferReason}>
          {t.transferring ? "Transferring…" : "Transfer"}
        </button>
      </div>
    </Dialog>
  );
}
