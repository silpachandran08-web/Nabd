"use client";

import styles from "./nursing.module.css";
import Icon from "../ui/Icon";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { VitalsHistoryResponse, VitalsResponse } from "@nabd/api-client/generated";
import { VITAL_FIELDS, EMPTY_VITALS, EMPTY_EXTRAS, BLOOD_SUGAR_UNIT, PAIN_SCORE_MAX, nurseVitalsErrors, nurseVitalsPayload, formatBp, VITALS_BLANK_HINT, type VitalKey, type VitalsForm, type VitalsExtras } from "@nabd/domain";
import Dialog from "../ui/Dialog";
import { Skeleton } from "../ui/StateViews";
import { can } from "../../lib/permissions";
import { api } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { PERM, ageSex, detailOf, isAdult, softWarning, type Row } from "./model";

// FEF-031: the vitals capture form (J8). Mounted only while a visit is open, so its eight state hooks reset per patient.
// Implausible values are rejected inline by nurseVitalsErrors (packages/domain, mirrors the backend VitalsWriteRequest).
export default function VitalsCaptureDialog({ entryId, row, canFlagPriority, onMarkUrgent, onClose }: {
  entryId: string; row: Row | undefined; canFlagPriority: boolean; onMarkUrgent: (id: string) => void; onClose: () => void;
}) {
  const vitalsEntryId = entryId;
  const vitalsRow = row;
  const [vitalsForm, setVitalsForm] = useState<VitalsForm>(EMPTY_VITALS);
  const [vitalsTouched, setVitalsTouched] = useState<Partial<Record<string, boolean>>>({});
  const [bpInput, setBpInput] = useState("");
  const [vitalsExtras, setVitalsExtras] = useState<VitalsExtras>(EMPTY_EXTRAS);
  const [vitalsAttempted, setVitalsAttempted] = useState(false);
  const [vitalsError, setVitalsError] = useState<string | null>(null);
  const [vitalsFlags, setVitalsFlags] = useState<string[] | null>(null);
  const [vitalsSubmitting, setVitalsSubmitting] = useState(false);
  const closeVitalsModal = onClose;
  const openUrgentPicker = onMarkUrgent;
  const vErrors = nurseVitalsErrors(vitalsForm, bpInput, vitalsExtras);
  const vitalsPatientId = vitalsRow?.patientId;
  // NUR-022: previous measurements for the open visit's patient (null = loading). One extra row is requested
  // because the current visit is excluded client-side (contract: server excludes nothing).
  const prevQ = useQuery({
    queryKey: key("vitals-previous", { patientId: vitalsPatientId }),
    queryFn: getter<VitalsHistoryResponse>(`/clinical/vitals/patient/${vitalsPatientId}/previous?limit=6`),
    enabled: !!vitalsEntryId && !!vitalsPatientId && can(PERM.vitalsRead),
  });
  const prevVitals = prevQ.data ? (Array.isArray(prevQ.data.items) ? prevQ.data.items : []).filter((v) => v.queueEntryId !== vitalsEntryId).slice(0, 5) : null;
  const prevError = prevQ.isError;
  const vitalsFormEmpty = VITAL_FIELDS.every((d) => vitalsForm[d.key].trim() === "") && !bpInput.trim() && !vitalsExtras.bloodSugar.trim() && !vitalsExtras.painScore.trim();

  async function submitVitals(e: React.FormEvent) {
    e.preventDefault();
    if (!vitalsEntryId) return;
    setVitalsError(null);
    // An empty or implausible save must never move the visit on to the doctor with no vitals.
    setVitalsAttempted(true);
    if (Object.keys(nurseVitalsErrors(vitalsForm, bpInput, vitalsExtras)).length > 0) return;
    setVitalsSubmitting(true);
    try {
      const saved = await api.patch<VitalsResponse>(`/clinical/vitals/${vitalsEntryId}`, nurseVitalsPayload(vitalsForm, bpInput, vitalsExtras));
      const flags = saved?.abnormalFlags ?? [];
      if (flags.length > 0) {
        setVitalsFlags(flags);
      } else {
        closeVitalsModal();
      }
    } catch (err) {
      setVitalsError(detailOf(err, "Couldn't save vitals."));
    } finally {
      setVitalsSubmitting(false);
    }
  }
  if (vitalsFlags) {
    return (
        <Dialog title="Vitals saved" onClose={closeVitalsModal}>
          <div>
            <div className={styles.formError} role="alert">
              {vitalsFlags.map((f) => <div key={f} className={styles.flagLine}><Icon name="alert" size={14} />{f}</div>)}
            </div>
            <div className={styles.modalActions}>
              <button type="button" className={styles.submitBtn} onClick={closeVitalsModal}>Done</button>
            </div>
          </div>
        </Dialog>
    );
  }
  return (
        <Dialog title={`Capture vitals${vitalsRow ? ` — ${vitalsRow.patientName}` : ""}`} size="lg"
          onClose={() => { if (!vitalsSubmitting && vitalsFormEmpty) closeVitalsModal(); }}>
          {vitalsRow && (
            <div className={styles.vitalsPatient}>
              <strong>{vitalsRow.patientName}</strong>
              <span className={styles.muted}>#{vitalsRow.tokenNumber}{ageSex(vitalsRow.dob, vitalsRow.gender) ? ` · ${ageSex(vitalsRow.dob, vitalsRow.gender)}` : ""}{vitalsRow.patientMrn ? ` · ${vitalsRow.patientMrn}` : ""}</span>
            </div>
          )}
          <div className={styles.vitalsLayout}>
          <form noValidate onSubmit={submitVitals}>
            <p className={styles.muted}>{VITALS_BLANK_HINT}</p>
            {vitalsAttempted && vErrors.form && <div className={styles.formError} role="alert">{vErrors.form}</div>}
            <div className={styles.fieldGrid}>
              {([
                { key: "bp", label: "Blood pressure", unit: "mmHg", value: bpInput, set: (v: string) => setBpInput(v), type: "text", mode: "numeric", ph: "120/80", soft: null },
                ...VITAL_FIELDS.filter((d) => d.key !== "bpSystolic" && d.key !== "bpDiastolic").map((d) => ({
                  key: d.key as string, label: d.label, unit: d.unit, value: vitalsForm[d.key], type: "number", mode: "decimal", ph: undefined, step: d.step, min: d.min, max: d.max,
                  set: (v: string) => setVitalsForm((prev) => ({ ...prev, [d.key]: v })), soft: d.key as VitalKey,
                })),
                { key: "bloodSugar", label: "Blood sugar", unit: BLOOD_SUGAR_UNIT, value: vitalsExtras.bloodSugar, type: "number", mode: "decimal", ph: undefined, step: "any", min: 0, soft: null,
                  set: (v: string) => setVitalsExtras((prev) => ({ ...prev, bloodSugar: v })) },
                { key: "painScore", label: "Pain score", unit: `/${PAIN_SCORE_MAX}`, value: vitalsExtras.painScore, type: "number", mode: "numeric", ph: undefined, step: "1", min: 0, max: PAIN_SCORE_MAX, soft: null,
                  set: (v: string) => setVitalsExtras((prev) => ({ ...prev, painScore: v })) },
              ] as { key: string; label: string; unit: string; value: string; type: string; mode: "numeric" | "decimal"; ph?: string; step?: string; min?: number; max?: number; soft: VitalKey | null; set: (v: string) => void }[]).map((d) => {
                const errKey = d.key as keyof typeof vErrors;
                const showErr = (vitalsAttempted || vitalsTouched[d.key]) ? vErrors[errKey] : undefined;
                const warn = showErr || !d.soft ? null : softWarning(d.soft, d.value, isAdult(vitalsRow?.dob ?? null));
                return (
                  <div className={styles.field} key={d.key}>
                    <label className={styles.label} htmlFor={d.key}>{d.label} ({d.unit})</label>
                    <div className={styles.unitInput}>
                      <input id={d.key} type={d.type} inputMode={d.mode} step={d.step} min={d.min} max={d.max} placeholder={d.ph}
                        className={styles.input} value={d.value} aria-invalid={!!showErr}
                        aria-describedby={showErr || warn ? `${d.key}-msg` : undefined}
                        onBlur={() => setVitalsTouched((prev) => ({ ...prev, [d.key]: true }))}
                        onChange={(e) => d.set(e.target.value)} />
                      <span className={styles.unit} aria-hidden="true">{d.unit}</span>
                    </div>
                    {showErr && <div className={styles.fieldError} id={`${d.key}-msg`} role="alert"><Icon name="alert" size={12} />{showErr}</div>}
                    {warn && <div className={styles.fieldWarn} id={`${d.key}-msg`}><Icon name="info" size={12} />{warn}</div>}
                  </div>
                );
              })}
            </div>
            {vitalsError && <div className={styles.formError} role="alert">{vitalsError}</div>}
            <div className={styles.modalActions}>
              {canFlagPriority && vitalsRow && !vitalsRow.priority && (
                <button type="button" className={`${styles.smallBtn} ${styles.pushStart}`} onClick={() => openUrgentPicker(vitalsRow.id)}>Mark urgent</button>
              )}
              <button type="button" className={styles.cancelBtn} onClick={closeVitalsModal}>Cancel</button>
              <button type="submit" className={styles.submitBtn} disabled={vitalsSubmitting}>{vitalsSubmitting ? "Saving…" : "Save and mark ready"}</button>
            </div>
          </form>
          {can(PERM.vitalsRead) && (
            <aside className={styles.prevPanel} aria-label="Previous measurements">
              <h3 className={styles.prevTitle}>Previous measurements</h3>
              {prevError ? (
                <div className={styles.formError} role="alert">
                  Couldn&apos;t load previous measurements.{" "}
                  {vitalsPatientId && vitalsEntryId && <button type="button" className={styles.smallBtn} onClick={() => void prevQ.refetch()}>Retry</button>}
                </div>
              ) : prevVitals === null ? (
                <Skeleton rows={3} label="Loading previous measurements" />
              ) : prevVitals.length === 0 ? (
                <p className={styles.muted}>No earlier measurements for this patient.</p>
              ) : (
                <div className={styles.prevScroll}>
                  <table className={styles.prevTable}>
                    <thead><tr><th scope="col">Recorded</th><th scope="col">BP</th><th scope="col">Pulse</th><th scope="col">Temp</th><th scope="col">SpO2</th><th scope="col">Weight</th><th scope="col">Height</th><th scope="col">Sugar ({BLOOD_SUGAR_UNIT})</th><th scope="col">Pain</th></tr></thead>
                    <tbody>
                      {prevVitals.map((v) => (
                        <tr key={v.queueEntryId}>
                          <td>{v.recordedAt ? new Date(v.recordedAt).toLocaleDateString("en-GB", { day: "2-digit", month: "short", year: "numeric" }) : "—"}</td>
                          <td>{formatBp(v)}</td><td>{v.pulseBpm ?? "—"}</td><td>{v.tempCelsius ?? "—"}</td><td>{v.spo2Percent ?? "—"}</td>
                          <td>{v.weightKg ?? "—"}</td><td>{v.heightCm ?? "—"}</td><td>{v.bloodSugar ?? "—"}</td><td>{v.painScore ?? "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </aside>
          )}
          </div>
        </Dialog>
  );
}
