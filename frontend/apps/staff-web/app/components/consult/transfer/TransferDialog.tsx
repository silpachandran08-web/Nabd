"use client";

import { useTranslations } from "next-intl";
import Dialog from "../../ui/Dialog";
import { Skeleton } from "../../ui/StateViews";
import type { Transfer } from "./useTransfer";
import styles from "../consult.module.css";
import p from "../consultParts.module.css";

export default function TransferDialog({ t }: { t: Transfer }) {
  const tx = useTranslations("consult.transferDialog");
  const g = useTranslations("glossary");
  if (!t.showTransfer) return null;
  return (
    <Dialog title={tx("transferToAnotherDepartment")} onClose={() => t.setShowTransfer(false)}>
      {t.transferTargets === null ? (
        <Skeleton rows={2} label={tx("loadingTransferOptions")} />
      ) : t.transferTargets.length === 0 ? (
        <p className={p.muted}>{tx("thisDepartmentIsnTConfigured")}</p>
      ) : (
        <>
          <div className={styles.formRow}>
            <label className={styles.fieldLabel} htmlFor="trDept">{tx("department")}</label>
            <select id="trDept" className={styles.select} value={t.transferDepartmentId} onChange={(e) => { t.setTransferDepartmentId(e.target.value); t.setTransferDoctorId(""); }}>
              <option value="">{tx("selectADepartment")}</option>
              {t.transferTargets.map((x) => <option key={x.departmentId} value={x.departmentId}>{x.departmentName}</option>)}
            </select>
          </div>
          {t.transferDepartmentId && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trDoc">{tx("doctor")}</label>
              <select id="trDoc" className={styles.select} value={t.transferDoctorId} onChange={(e) => t.setTransferDoctorId(e.target.value)}>
                <option value="">{tx("selectADoctor")}</option>
                {t.transferTargets.find((x) => x.departmentId === t.transferDepartmentId)?.doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </div>
          )}
          {t.transferDoctorId && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trReason">{g("soap.reason")}</label>
              <select id="trReason" className={styles.select} value={t.transferReason} onChange={(e) => t.setTransferReason(e.target.value)}>
                <option value="">{tx("selectAReason")}</option>
                <option value="wrong_department">{tx("wrongDepartment")}</option>
                <option value="doctor_unavailable">{tx("doctorUnavailable")}</option>
                <option value="patient_request">{tx("patientRequest")}</option>
                <option value="other">{tx("other")}</option>
              </select>
            </div>
          )}
          {t.transferReason === "other" && (
            <div className={styles.formRow}>
              <label className={styles.fieldLabel} htmlFor="trNote">{tx("note")}</label>
              <input id="trNote" className={styles.select} maxLength={200} value={t.transferNote} onChange={(e) => t.setTransferNote(e.target.value)} />
            </div>
          )}
        </>
      )}
      {t.transferError && <div className={`${p.notice} ${p.noticeDanger}`} role="alert">{t.transferError}</div>}
      <div className={p.dialogActions}>
        <button type="button" className={p.btn} onClick={() => t.setShowTransfer(false)}>{tx("cancel")}</button>
        <button type="button" className={p.btnPrimary} onClick={t.submitTransfer} disabled={t.transferring || !t.transferDepartmentId || !t.transferDoctorId || !t.transferReason}>
          {t.transferring ? tx("transferring") : tx("transfer")}
        </button>
      </div>
    </Dialog>
  );
}
