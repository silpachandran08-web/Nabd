"use client";

import { useState } from "react";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload, VITALS_BLANK_HINT, type VitalsForm } from "@nabd/domain";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { api } from "../../../lib/api";
import { failText } from "../failText";

export default function VitalsDialog({ entryId, onClose, onSaved }: { entryId: string; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState<VitalsForm>(EMPTY_VITALS);
  const [attempted, setAttempted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const errs = vitalsErrors(form);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setAttempted(true);
    if (Object.keys(errs).length > 0) return;
    setSubmitting(true);
    try {
      await api.patch(`/clinical/vitals/${entryId}`, vitalsPayload(form));
      onSaved();
    } catch (err) {
      setError(failText(err, "Couldn't save vitals."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog title="Record vitals" onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className={styles.muted}>{VITALS_BLANK_HINT}</p>
        {attempted && errs.form && <div className={styles.formError} role="alert">{errs.form}</div>}
        {VITAL_FIELDS.map((d) => {
          const err = attempted ? errs[d.key] : undefined;
          return (
            <div className={styles.field} key={d.key}>
              <label className={styles.label} htmlFor={d.key}>{d.label} ({d.unit})</label>
              <input id={d.key} type="number" inputMode="decimal" step={d.step} min={d.min} max={d.max} className={styles.input}
                value={form[d.key]} aria-invalid={!!err} aria-describedby={err ? `${d.key}-err` : undefined}
                onChange={(e) => setForm((prev) => ({ ...prev, [d.key]: e.target.value }))} />
              {err && <div className={styles.formError} id={`${d.key}-err`} role="alert">{err}</div>}
            </div>
          );
        })}
        {error && <div className={styles.formError} role="alert">{error}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.submitBtn} disabled={submitting}>{submitting ? "Saving…" : "Save vitals"}</button>
        </div>
      </form>
    </Dialog>
  );
}
