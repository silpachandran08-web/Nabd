"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import StatusPill from "../../ui/StatusPill";
import { EmptyState } from "../../ui/StateViews";
import { ApiError, api } from "../../../lib/api";
import { prefix } from "../../../lib/query";
import { failText } from "../failText";
import type { Staff } from "../types";

const DELAY_PRESETS = [15, 30, 45, 60];

export function DelayForm({ doctor, waiting, onClose }: { doctor?: Staff; waiting: number; onClose: () => void }) {
  const qc = useQueryClient();
  const t = useTranslations("arrivals");
  const [minutes, setMinutes] = useState("15");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mins = Number(minutes);
  const valid = minutes.trim() !== "" && Number.isInteger(mins) && mins >= 1;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!doctor) return;
    setBusy(true);
    try {
      try {
        await api.post(`/doctors/${doctor.id}/delay`, { delayMinutes: mins, reason: reason || null, broadcast: true });
      } catch (err) {
        // ACC-024: one delay at a time; a second tap changes the active one.
        if (!(err instanceof ApiError && err.status === 409)) throw err;
        const rows = await api.get<{ delayId?: string; active?: boolean }[]>(`/doctors/${doctor.id}/delay`);
        const active = rows.find((r) => r.active);
        if (!active?.delayId) throw err;
        await api.patch(`/doctors/${doctor.id}/delay/${active.delayId}`, { minutes: mins });
      }
      void qc.invalidateQueries({ queryKey: prefix("doctor-delay") });
      void qc.invalidateQueries({ queryKey: prefix("wait-estimate") });
      onClose();
    } catch (err) {
      setError(failText(err, t("delay.fail"), t("page.denied")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={t("delay.title", { name: doctor?.name ?? "" })} onClose={onClose}>
      <form onSubmit={submit}>
        <div className={styles.field}>
          <span className={styles.label} id="delayPresetLabel">{t("delay.runningLate")}</span>
          <div className={styles.presets} role="group" aria-labelledby="delayPresetLabel">
            {DELAY_PRESETS.map((m) => (
              <button type="button" key={m} className={mins === m ? styles.presetActive : styles.preset} aria-pressed={mins === m} onClick={() => setMinutes(String(m))}>{t("delay.presetMin", { m })}</button>
            ))}
          </div>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="delayMinutes">{t("delay.other")}</label>
          <input id="delayMinutes" type="number" min="1" step="1" className={styles.input} value={minutes} onChange={(e) => setMinutes(e.target.value)} required />
          {!valid && minutes !== "" && <span className={styles.formError} role="alert">{t("delay.invalid")}</span>}
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="delayReason">{t("delay.reason")}</label>
          <input id="delayReason" className={styles.input} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("delay.reasonPlaceholder")} />
        </div>
        <div className={styles.delayPreview} aria-label={t("delay.previewLabel")}>
          <span className={styles.label}>{t("delay.preview")}</span>
          {valid ? (
            <span><StatusPill tone="danger" label={t("side.delayed")} icon="clock" /> {reason.trim() ? t("side.lateReason", { min: mins, reason: reason.trim() }) : t("side.late", { min: mins })}</span>
          ) : (
            <span className={styles.muted}>{t("delay.pick")}</span>
          )}
          <span className={styles.muted}>{waiting === 0 ? t("delay.noneWaiting") : t("delay.affected", { count: waiting })}</span>
        </div>
        <ul className={styles.ladder} aria-label={t("delay.ladderLabel")}>
          <li>{t("delay.ladderQueue")}</li>
          <li>{t("delay.ladderPatient")}</li>
        </ul>
        {error && <div className={styles.formError} role="alert">{error}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
          <button type="submit" className={styles.submitBtn} disabled={busy || !valid}>{busy ? t("delay.announcing") : t("delay.announce")}</button>
        </div>
      </form>
    </Dialog>
  );
}

export function DelayPicker({ staff, onPick, onClose }: { staff: Staff[]; onPick: (doctorId: string) => void; onClose: () => void }) {
  const t = useTranslations("arrivals");
  return (
    <Dialog title={t("delay.pickTitle")} onClose={onClose}>
      {staff.length === 0 ? (
        <EmptyState icon="users" title={t("side.noDoctors")} />
      ) : (
        <div className={styles.searchResults}>
          {staff.map((s) => <button type="button" key={s.id} className={styles.searchResultRow} onClick={() => onPick(s.id)}>{s.name}</button>)}
        </div>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>{t("dialog.cancel")}</button>
      </div>
    </Dialog>
  );
}
