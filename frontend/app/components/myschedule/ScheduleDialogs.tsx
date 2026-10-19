"use client";

import { useState, type FormEvent } from "react";
import styles from "./mySchedule.module.css";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { WEEKDAYS, hhmm, type LeaveInput, type WorkingHours, type WorkingHoursInput } from "./useMySchedule";
import { clinicToday } from "../../lib/clinicTime";

const toMin = (t: string) => { const [h, m] = t.split(":").map(Number); return h * 60 + m; };

export function WorkingHoursDialog({ existing, editing, onClose, onSave }: {
  existing: WorkingHours[];
  editing?: WorkingHours;
  onClose: () => void;
  onSave: (i: WorkingHoursInput) => Promise<string | null>;
}) {
  const [day, setDay] = useState(editing?.dayOfWeek ?? 1);
  const [start, setStart] = useState(editing ? hhmm(editing.startTime) : "09:00");
  const [end, setEnd] = useState(editing ? hhmm(editing.endTime) : "13:00");
  const [slot, setSlot] = useState(String(editing?.slotMinutes ?? 15));
  const [cap, setCap] = useState(editing?.maxPatients != null ? String(editing.maxPatients) : "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const slotN = Number(slot);
    const capN = cap.trim() === "" ? null : Number(cap);
    if (!start || !end || toMin(end) <= toMin(start)) return setError("The end time must be after the start time.");
    if (!Number.isInteger(slotN) || slotN < 5 || slotN > 240) return setError("Slot length must be a whole number between 5 and 240 minutes.");
    if (toMin(end) - toMin(start) < slotN) return setError("The block is shorter than one slot.");
    if (capN !== null && (!Number.isInteger(capN) || capN < 1)) return setError("Maximum patients must be 1 or more, or left empty for no cap.");
    const clash = existing.find((h) => h.id !== editing?.id && h.dayOfWeek === day && toMin(start) < toMin(h.endTime) && toMin(h.startTime) < toMin(end));
    if (clash) return setError(`This overlaps your existing ${WEEKDAYS[day]} block ${hhmm(clash.startTime)}–${hhmm(clash.endTime)}.`);
    setBusy(true);
    const err = await onSave({ dayOfWeek: day, startTime: start, endTime: end, slotMinutes: slotN, maxPatients: capN });
    setBusy(false);
    if (err) setError(err); else onClose();
  }

  return (
    <Dialog title={editing ? "Edit working hours" : "Add working hours"} onClose={onClose}>
      <form className={styles.form} onSubmit={submit} noValidate>
        <label className={styles.field}>Weekday
          <select className={styles.input} value={day} onChange={(e) => setDay(Number(e.target.value))}>
            {WEEKDAYS.map((w, i) => <option key={w} value={i}>{w}</option>)}
          </select>
        </label>
        <div className={styles.two}>
          <label className={styles.field}>From<input className={styles.input} type="time" value={start} onChange={(e) => setStart(e.target.value)} required /></label>
          <label className={styles.field}>To<input className={styles.input} type="time" value={end} onChange={(e) => setEnd(e.target.value)} required /></label>
        </div>
        <div className={styles.two}>
          <label className={styles.field}>Slot length (minutes)<input className={styles.input} inputMode="numeric" value={slot} onChange={(e) => setSlot(e.target.value)} /></label>
          <label className={styles.field}>Max patients (optional)<input className={styles.input} inputMode="numeric" value={cap} onChange={(e) => setCap(e.target.value)} placeholder="No cap" /></label>
        </div>
        <p className={styles.note}><Icon name="info" size={12} /> {editing ? "This changes the block for every week." : "This adds a block to your weekly pattern."}</p>
        {error && <p className={styles.error} role="alert"><Icon name="alert" size={14} /> {error}</p>}
        <div className={styles.formActions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Save hours"}</button>
        </div>
      </form>
    </Dialog>
  );
}

export function LeaveDialog({ onClose, onSave }: {
  onClose: () => void;
  onSave: (i: LeaveInput) => Promise<{ error: string } | { affected: string[] }>;
}) {
  const today = clinicToday();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [affected, setAffected] = useState<string[] | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!from || !to) return setError("Choose the first and last day of leave.");
    if (to < from) return setError("The last day cannot be before the first day.");
    setBusy(true);
    const r = await onSave({ dateFrom: from, dateTo: to, reason: reason.trim() || null });
    setBusy(false);
    if ("error" in r) setError(r.error); else setAffected(r.affected);
  }

  if (affected) {
    return (
      <Dialog title="Leave recorded" onClose={onClose}>
        <div className={styles.form}>
          <p className={styles.ok}><Icon name="check" size={14} /> Your leave is saved and takes effect immediately.</p>
          <p className={styles.note}><Icon name="info" size={12} /> Existing bookings in this period are not moved or cancelled automatically.</p>
          {affected.length > 0 && (
            <div className={styles.warn} role="status">
              <Icon name="alert" size={14} />
              <div>
                <strong>Active treatment packages restricted to you may be affected:</strong>
                <ul className={styles.affected}>{affected.map((n) => <li key={n}>{n}</li>)}</ul>
              </div>
            </div>
          )}
          <div className={styles.formActions}><button type="button" className={styles.btnPrimary} onClick={onClose}>Done</button></div>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog title="Apply leave" onClose={onClose}>
      <form className={styles.form} onSubmit={submit} noValidate>
        <div className={styles.two}>
          <label className={styles.field}>First day<input className={styles.input} type="date" value={from} onChange={(e) => setFrom(e.target.value)} required /></label>
          <label className={styles.field}>Last day<input className={styles.input} type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} required /></label>
        </div>
        <label className={styles.field}>Reason (optional)<input className={styles.input} value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} /></label>
        <p className={styles.note}><Icon name="info" size={12} /> Leave is recorded directly, with no approval step, and removes you from booking on those days.</p>
        {error && <p className={styles.error} role="alert"><Icon name="alert" size={14} /> {error}</p>}
        <div className={styles.formActions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Record leave"}</button>
        </div>
      </form>
    </Dialog>
  );
}
