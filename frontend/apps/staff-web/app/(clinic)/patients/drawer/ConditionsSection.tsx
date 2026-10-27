"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import styles from "../patients.module.css";
import { Skeleton } from "../../../components/ui/StateViews";
import { api, ApiError } from "../../../lib/api";
import { key } from "../../../lib/query";
import { FieldError, type FormErrors } from "../../../lib/formErrors";
import type { PatientDetail } from "../patientsModel";
import { useConditions, useRefreshPatient } from "./usePatientQueries";

// Chronic conditions: own query, own loading / error state.
export default function ConditionsSection({ patient, clinicalEdit, fe, onRegistryChanged }: { patient: PatientDetail; clinicalEdit: boolean; fe: FormErrors; onRegistryChanged: () => void }) {
  const qc = useQueryClient();
  const q = useConditions(patient.id, true);
  const refreshPatient = useRefreshPatient(patient.id);
  const [newCondition, setNewCondition] = useState({ condition: "", reviewDueDate: "" });
  const [conditionBusy, setConditionBusy] = useState(false);
  const conditionLoad = q.isError ? "error" : q.isPending ? "loading" : "ok";
  const conditions = q.data ?? [];
  const refreshConditions = () => qc.invalidateQueries({ queryKey: key("patient-conditions", { id: patient.id }) });

  async function addCondition() {
    if (!newCondition.condition.trim() || conditionBusy) return;
    setConditionBusy(true);
    fe.clear();
    let saved = false;
    try {
      await api.post(`/clinical/patients/${patient.id}/conditions`, { condition: newCondition.condition, reviewDueDate: newCondition.reviewDueDate || null });
      saved = true;
    } catch (e) {
      fe.fail(e, e instanceof ApiError ? "Couldn't save the condition. It has NOT been recorded." : "Couldn't reach the server. The condition has NOT been recorded.");
    } finally {
      setConditionBusy(false);
    }
    if (!saved) return;
    setNewCondition({ condition: "", reviewDueDate: "" });
    void refreshConditions();
    onRegistryChanged(); // Follow-up due flag/count may have changed
    await refreshPatient();
  }

  async function resolveCondition(id: string) {
    fe.clear();
    try {
      await api.patch(`/clinical/conditions/${id}/resolve`);
    } catch (e) {
      fe.set(e instanceof ApiError ? "Couldn't resolve the condition. It is still active." : "Couldn't reach the server. The condition is still active.");
      return;
    }
    void refreshConditions();
    onRegistryChanged(); // Follow-up due flag/count may have changed
    await refreshPatient();
  }

  return (
                      <div className={styles.section}>
                        <div className={styles.sectionLabel}>Chronic conditions</div>
                        {conditionLoad === "error" ? (
                          <div className={styles.errorInline} role="alert">Couldn&apos;t load conditions.</div>
                        ) : conditionLoad === "loading" ? (
                          <Skeleton rows={1} label="Loading conditions" />
                        ) : conditions.length === 0 ? (
                          <div className={styles.muted}>None recorded</div>
                        ) : (
                          conditions.map((c) => (
                            <div key={c.id} className={styles.allergyRow}>
                              <span>{c.condition}{c.reviewDueDate ? ` · review due ${new Date(c.reviewDueDate).toLocaleDateString()}` : ""}</span>
                              {clinicalEdit && <button className={styles.linkBtn} onClick={() => resolveCondition(c.id)}>Resolve</button>}
                            </div>
                          ))
                        )}
                        {clinicalEdit && (
                          <div className={styles.allergyForm}>
                            <input className={styles.smallInput} aria-label="Condition" placeholder="Condition" value={newCondition.condition} {...fe.attrs("condition")}
                              onChange={(e) => { fe.clearField("condition"); setNewCondition((p) => ({ ...p, condition: e.target.value })); }} />
                            <FieldError fe={fe} name="condition" className={styles.errorInline} />
                            <input className={styles.smallInput} aria-label="Review due date" type="date" value={newCondition.reviewDueDate} {...fe.attrs("reviewDueDate")}
                              onChange={(e) => { fe.clearField("reviewDueDate"); setNewCondition((p) => ({ ...p, reviewDueDate: e.target.value })); }} />
                            <FieldError fe={fe} name="reviewDueDate" className={styles.errorInline} />
                            <button className={styles.linkBtn} disabled={conditionBusy || !newCondition.condition.trim()} onClick={addCondition}>Add</button>
                          </div>
                        )}
                      </div>
  );
}
