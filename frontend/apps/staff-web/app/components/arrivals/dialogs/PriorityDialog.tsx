"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import { api } from "../../../lib/api";
import { failText } from "../failText";

// NB-143: a small fixed reason-code catalogue for this one call site (NB-011's generic reason-code service doesn't exist).
const PRIORITY_REASON_CODES = [
  "Chest pain / cardiac symptoms", "Breathing difficulty", "Severe bleeding",
  "High fever in infant", "Post-operative complication", "Severe pain", "Other",
] as const;

export default function PriorityDialog({ entryId, onClose, onSaved }: { entryId: string; onClose: () => void; onSaved: () => void }) {
  const t = useTranslations("arrivals");
  const [code, setCode] = useState<string>(PRIORITY_REASON_CODES[0]);
  const [other, setOther] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const reason = code === "Other" ? other.trim() : code;
    if (reason.length < 3) return; // QueueReorderRequest.reason: @Size(min = 3)
    setSubmitting(true);
    try {
      await api.post(`/queue/${entryId}/reorder`, { priority: true, reason });
      onSaved();
    } catch (err) {
      setError(failText(err, t("priority.fail"), t("page.denied")));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog title={t("priority.title")} onClose={onClose}>
      <form onSubmit={submit}>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="priorityReason">{t("priority.reason")}</label>
          <select id="priorityReason" className={styles.select} value={code} onChange={(e) => setCode(e.target.value)}>
            {PRIORITY_REASON_CODES.map((r, i) => <option key={r} value={r}>{t(`priority.c${i}`)}</option>)}
          </select>
        </div>
        {code === "Other" && (
          <div className={styles.field}>
            <label className={styles.label} htmlFor="priorityOther">{t("priority.specify")}</label>
            <input id="priorityOther" className={styles.input} minLength={3} value={other} onChange={(e) => setOther(e.target.value)} />
          </div>
        )}
        {error && <div className={styles.formError} role="alert">{error}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
          <button type="submit" className={styles.submitBtn} disabled={submitting || (code === "Other" && other.trim().length < 3)}>{submitting ? t("priority.saving") : t("priority.title")}</button>
        </div>
      </form>
    </Dialog>
  );
}
