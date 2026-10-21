"use client";

// "Move queue": pick an active patient, then the department/doctor transfer picker (POST /queue/{id}/transfer).
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { STALE } from "@nabd/api-client/queries";
import type { TransferTargetResponse } from "@nabd/api-client/generated";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { EmptyState, Skeleton } from "../../ui/StateViews";
import { api } from "../../../lib/api";
import { getter, key } from "../../../lib/query";
import { failText } from "../failText";
import { bucketOf } from "../board/boardLogic";
import type { Row } from "../types";

// REC-021 transfer reasons (S4 contract values); `other` takes a free-text note (<=200 chars).
const MOVE_REASONS: [string, string][] = [["wrong_department", "Wrong department"], ["doctor_unavailable", "Doctor unavailable"], ["patient_request", "Patient request"], ["other", "Other"]];

export default function MoveDialog({ rows, onClose, onMoved }: { rows: Row[]; onClose: () => void; onMoved: () => void }) {
  const qc = useQueryClient();
  const [entryId, setEntryId] = useState("");
  const [targets, setTargets] = useState<TransferTargetResponse[] | null>(null);
  const [departmentId, setDepartmentId] = useState("");
  const [doctorId, setDoctorId] = useState("");
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function selectEntry(id: string) {
    setEntryId(id); setDepartmentId(""); setDoctorId(""); setTargets(null); setReason(""); setNote(""); setError(null);
    const entry = rows.find((r) => r.id === id);
    if (!entry) return;
    try {
      setTargets(await qc.fetchQuery({
        queryKey: key("transfer-targets", { departmentId: entry.departmentId }), staleTime: STALE.departments,
        queryFn: getter<TransferTargetResponse[]>(`/departments/${entry.departmentId}/transfer-targets`),
      }));
    } catch {
      setError("Couldn't load transfer options.");
      setTargets([]);
    }
  }

  // The reason is required only when the department changes (same-department = doctor change).
  const movingDept = departmentId !== "" && departmentId !== rows.find((r) => r.id === entryId)?.departmentId;
  const reasonMissing = movingDept && !reason;
  async function submit() {
    if (!entryId || !departmentId || !doctorId) return;
    if (reasonMissing) { setError("Choose a reason for moving the patient to another department."); return; }
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/queue/${entryId}/transfer`, {
        toDepartmentId: departmentId, doctorId,
        ...(reason ? { reason, ...(reason === "other" && note.trim() ? { reasonNote: note.trim() } : {}) } : {}),
      });
      onMoved();
    } catch (err) {
      setError(failText(err, "Couldn't move the patient."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog title="Move queue" onClose={onClose}>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="moveEntry">Patient</label>
        <select id="moveEntry" className={styles.select} value={entryId} onChange={(e) => void selectEntry(e.target.value)}>
          <option value="">Select a patient…</option>
          {rows.filter((r) => bucketOf(r.status) === "waiting").map((r) => <option key={r.id} value={r.id}>#{r.tokenNumber} · {r.patientName}</option>)}
        </select>
      </div>
      {entryId && (
        targets === null ? (
          <Skeleton rows={2} label="Loading transfer options" />
        ) : targets.length === 0 ? (
          <EmptyState icon="info" title="This department isn't configured to transfer patients anywhere" />
        ) : (
          <>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="moveDepartment">Department</label>
              <select id="moveDepartment" className={styles.select} value={departmentId} onChange={(e) => { setDepartmentId(e.target.value); setDoctorId(""); }}>
                <option value="">Select a department…</option>
                {targets.map((t) => <option key={t.departmentId} value={t.departmentId}>{t.departmentName}</option>)}
              </select>
            </div>
            {departmentId && (
              <div className={styles.field}>
                <label className={styles.label} htmlFor="moveDoctor">Doctor</label>
                <select id="moveDoctor" className={styles.select} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
                  <option value="">Select a doctor…</option>
                  {targets.find((t) => t.departmentId === departmentId)?.doctors?.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </div>
            )}
            {doctorId && (
              <>
                <div className={styles.field}>
                  <label className={styles.label} htmlFor="moveReason">Reason{movingDept ? " (required)" : ""}</label>
                  <select id="moveReason" className={styles.select} value={reason} required={movingDept} onChange={(e) => setReason(e.target.value)}>
                    <option value="">Select a reason…</option>
                    {MOVE_REASONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                  </select>
                </div>
                {reason === "other" && (
                  <div className={styles.field}>
                    <label className={styles.label} htmlFor="moveReasonNote">Note</label>
                    <input id="moveReasonNote" className={styles.input} maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
                  </div>
                )}
              </>
            )}
          </>
        )
      )}
      {error && <div className={styles.formError} role="alert">{error}</div>}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
        <button type="button" className={styles.submitBtn} disabled={submitting || !departmentId || !doctorId || reasonMissing} onClick={() => void submit()}>{submitting ? "Moving…" : "Move"}</button>
      </div>
    </Dialog>
  );
}
