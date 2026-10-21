"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import styles from "../arrivals.module.css";
import Dialog from "../../ui/Dialog";
import StatusPill from "../../ui/StatusPill";
import { EmptyState } from "../../ui/StateViews";
import { api } from "../../../lib/api";
import { prefix } from "../../../lib/query";
import { failText } from "../failText";
import type { Staff } from "../types";

const DELAY_PRESETS = [10, 15, 30, 45, 60];

export function DelayForm({ doctor, waiting, onClose }: { doctor?: Staff; waiting: number; onClose: () => void }) {
  const qc = useQueryClient();
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
      await api.post(`/doctors/${doctor.id}/delay`, { delayMinutes: mins, reason: reason || null });
      void qc.invalidateQueries({ queryKey: prefix("doctor-delay") });
      void qc.invalidateQueries({ queryKey: prefix("wait-estimate") });
      onClose();
    } catch (err) {
      setError(failText(err, "Couldn't announce the delay."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={`Announce delay — ${doctor?.name ?? ""}`} onClose={onClose}>
      <form onSubmit={submit}>
        <div className={styles.field}>
          <span className={styles.label} id="delayPresetLabel">Running late by</span>
          <div className={styles.presets} role="group" aria-labelledby="delayPresetLabel">
            {DELAY_PRESETS.map((m) => (
              <button type="button" key={m} className={mins === m ? styles.presetActive : styles.preset} aria-pressed={mins === m} onClick={() => setMinutes(String(m))}>{m} min</button>
            ))}
          </div>
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="delayMinutes">Other (minutes)</label>
          <input id="delayMinutes" type="number" min="1" step="1" className={styles.input} value={minutes} onChange={(e) => setMinutes(e.target.value)} required />
          {!valid && minutes !== "" && <span className={styles.formError} role="alert">Enter a whole number of minutes, 1 or more.</span>}
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="delayReason">Reason (optional)</label>
          <input id="delayReason" className={styles.input} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Emergency case running long" />
        </div>
        <div className={styles.delayPreview} aria-label="Delay preview">
          <span className={styles.label}>Preview</span>
          {valid ? (
            <span><StatusPill tone="danger" label="Delayed" icon="clock" /> Running {mins}m late{reason.trim() ? ` — ${reason.trim()}` : ""}</span>
          ) : (
            <span className={styles.muted}>Pick or enter the delay to see the notice.</span>
          )}
          <span className={styles.muted}>{waiting === 0 ? "No patients are waiting for this doctor." : `${waiting} waiting ${waiting === 1 ? "patient is" : "patients are"} affected.`}</span>
        </div>
        <ul className={styles.ladder} aria-label="How the delay is delivered">
          <li>Queue sidebar: shown to the front desk as soon as you announce.</li>
          {/* shortcut: no patient-messaging endpoint for delays yet; replace with a real channel ladder (WhatsApp, then SMS) when the backend sends them. */}
          <li className={styles.muted}>Patient message (WhatsApp, then SMS): not available yet.</li>
        </ul>
        {error && <div className={styles.formError} role="alert">{error}</div>}
        <div className={styles.modalActions}>
          <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.submitBtn} disabled={busy || !valid}>{busy ? "Announcing…" : "Announce delay"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export function DelayPicker({ staff, onPick, onClose }: { staff: Staff[]; onPick: (doctorId: string) => void; onClose: () => void }) {
  return (
    <Dialog title="Notify delay — pick a doctor" onClose={onClose}>
      {staff.length === 0 ? (
        <EmptyState icon="users" title="No doctors on the roster" />
      ) : (
        <div className={styles.searchResults}>
          {staff.map((s) => <button type="button" key={s.id} className={styles.searchResultRow} onClick={() => onPick(s.id)}>{s.name}</button>)}
        </div>
      )}
      <div className={styles.modalActions}>
        <button type="button" className={styles.cancelBtn} onClick={onClose}>Cancel</button>
      </div>
    </Dialog>
  );
}
