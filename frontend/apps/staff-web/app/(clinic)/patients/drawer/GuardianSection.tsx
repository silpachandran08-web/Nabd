"use client";

import { useState } from "react";
import styles from "../patients.module.css";
import StatusPill from "../../../components/ui/StatusPill";
import { api, ApiError } from "../../../lib/api";
import type { PatientDetail } from "../patientsModel";
import { useRefreshPatient } from "./usePatientQueries";
import ConfirmPortal from "./ConfirmPortal";

export default function GuardianSection({ patient, patientsEdit, onRegistryChanged }: { patient: PatientDetail; patientsEdit: boolean; onRegistryChanged: () => void }) {
  const drawerPatient = patient;
  const refreshPatient = useRefreshPatient(patient.id);
  const [confirmGuardian, setConfirmGuardian] = useState(false);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  if (!patient.guardianId) return null;

  /** NB-085 / CGV-001: dedicated DELETE /patients/{id}/guardian (204). It touches only the guardian link,
   * so address and national ID can never be wiped; the server audits it and withdraws the
   * guardian_access consent in the same write. */
  async function revokeGuardian() {
    if (confirmBusy) return;
    setConfirmBusy(true);
    setConfirmError(null);
    let revoked = false;
    try {
      await api.delete(`/patients/${drawerPatient.id}/guardian`);
      revoked = true;
    } catch (e) {
      setConfirmError(e instanceof ApiError ? "Couldn't revoke guardian access. It is still active." : "Couldn't reach the server. Guardian access is still active.");
    } finally {
      setConfirmBusy(false);
    }
    if (!revoked) return;
    setConfirmGuardian(false);
    onRegistryChanged();
    await refreshPatient();
  }

  return (
    <>
                    <div className={styles.section}>
                      <div className={styles.sectionLabel}>Guardian / proxy</div>
                      <div className={styles.allergyRow}>
                        <span className={styles.guardianCell}>
                          {drawerPatient.guardianName ?? <span className={styles.muted}>Name not on file</span>}
                          {drawerPatient.guardianConsentGrantedAt ? (
                            <StatusPill tone="ok" label={`Consent captured ${new Date(drawerPatient.guardianConsentGrantedAt).toLocaleDateString()}`} />
                          ) : (
                            <StatusPill tone="danger" label="Consent missing" />
                          )}
                        </span>
                        {!drawerPatient.isMinor && patientsEdit && (
                          <button className={styles.linkBtn} onClick={() => { setConfirmError(null); setConfirmGuardian(true); }}>Revoke access</button>
                        )}
                      </div>
                      {drawerPatient.isMinor && (
                        <div className={styles.muted}>A minor&apos;s guardian can&apos;t be removed — reassign instead. At 18 the patient appears in Guardian reviews due.</div>
                      )}
                    </div>
      {confirmGuardian && (
        <ConfirmPortal title="Revoke guardian access" confirmLabel="Revoke access"
          message={`${drawerPatient.guardianName ?? "The guardian"} will no longer be able to act for ${drawerPatient.name}, and the guardian consent is withdrawn.`}
          busy={confirmBusy} error={confirmError} onConfirm={() => void revokeGuardian()} onClose={() => setConfirmGuardian(false)} />
      )}
    </>
  );
}
