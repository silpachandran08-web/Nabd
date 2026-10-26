"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { VITAL_FIELDS, EMPTY_VITALS, vitalsErrors, vitalsPayload, type VitalsForm } from "@nabd/domain";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { api } from "../../../lib/api";
import { failText } from "../failText";

export default function VitalsDialog({ entryId, onClose, onSaved }: { entryId: string; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("arrivals");
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
      setError(failText(err, t("vitals.fail"), t("page.denied")));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog title={t("vitals.title")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className={styles.muted}>{t("vitals.blankHint")}</p>
        {attempted && errs.form && <div className={styles.formError} role="alert">{errs.form}</div>}
        {VITAL_FIELDS.map((d) => {
          const err = attempted ? errs[d.key] : undefined;
          return (
            <div className={styles.field} key={d.key}>
              <label className={styles.label} htmlFor={d.key}>{t.has(`vitals.${d.key}`) ? t(`vitals.${d.key}`) : d.label} ({d.unit})</label>
              <input id={d.key} type="number" inputMode="decimal" step={d.step} min={d.min} max={d.max} className={styles.input}
                value={form[d.key]} aria-invalid={!!err} aria-describedby={err ? `${d.key}-err` : undefined}
                onChange={(e) => setForm((prev) => ({ ...prev, [d.key]: e.target.value }))} />
              {err && <div className={styles.formError} id={`${d.key}-err`} role="alert">{err}</div>}
            </div>
          );
        })}
        {error && <div className={styles.formError} role="alert">{error}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
          <button type="submit" className={styles.submitBtn} disabled={submitting}>{submitting ? t("vitals.saving") : t("vitals.save")}</button>
        </div>
      </form>
    </Dialog>
  );
}
