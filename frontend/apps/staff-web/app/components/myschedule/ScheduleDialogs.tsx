"use client";

import { useEffect, useState, type FormEvent } from "react";
import styles from "./mySchedule.module.css";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { WEEKDAYS, hhmm, type AffectedBooking, type LeaveInput, type WorkingHours, type WorkingHoursInput } from "./useMySchedule";
import { clinicToday, formatClinicDate, formatClinicTime } from "../../lib/clinicTime";
import { hoursLabel } from "./hoursLabel";

const toMin = (t: string) => { const [h, m] = t.split(":").map(Number); return h * 60 + m; };
/** ACC-005: minutes from start to end; an end at or before the start means the next calendar day (the server checks it ends by the day start). */
const spanMin = (s: string, e: string) => (toMin(e) - toMin(s) + 1440) % 1440;

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
  // ACC-011/012: how the session is run
  const [mode, setMode] = useState(editing?.sessionMode ?? "slot");
  const [onlineCap, setOnlineCap] = useState(editing?.onlineCap != null ? String(editing.onlineCap) : "");
  const [label, setLabel] = useState(editing?.label ?? "");
  const [ratio, setRatio] = useState(String(editing?.walkInRatio ?? 3));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const onlineN = onlineCap.trim() === "" ? undefined : Number(onlineCap);
    const slotN = Number(slot);
    const capN = cap.trim() === "" ? null : Number(cap);
    if (!start || !end || toMin(end) === toMin(start)) return setError("The end time must differ from the start time.");
    if (!Number.isInteger(slotN) || slotN < 5 || slotN > 240) return setError("Slot length must be a whole number between 5 and 240 minutes.");
    if (spanMin(start, end) < slotN) return setError("The block is shorter than one slot.");
    if (capN !== null && (!Number.isInteger(capN) || capN < 1)) return setError("Maximum patients must be 1 or more, or left empty for no cap.");
    if (mode !== "slot" && capN === null) return setError("Token and mixed sessions need a maximum number of patients.");
    if (onlineN !== undefined && (!Number.isInteger(onlineN) || onlineN < 0 || (capN !== null && onlineN > capN))) return setError("The online cap must be a whole number, not more than the maximum patients.");
    const clash = existing.find((h) => h.id !== editing?.id && h.dayOfWeek === day && toMin(start) < toMin(h.startTime) + spanMin(h.startTime, h.endTime) && toMin(h.startTime) < toMin(start) + spanMin(start, end));
    if (clash) return setError(`This overlaps your existing ${WEEKDAYS[day]} block ${hoursLabel(clash)}.`);
    setBusy(true);
    const err = await onSave({ dayOfWeek: day, startTime: start, endTime: end, slotMinutes: slotN, maxPatients: capN, sessionMode: mode as WorkingHoursInput["sessionMode"], onlineCap: onlineN, label: label.trim() || undefined, walkInRatio: Number(ratio) });
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
        {start && end && toMin(end) < toMin(start) && <p className={styles.note}><Icon name="info" size={12} /> Ends at {end} the next day (+1). It must end by the clinic day start.</p>}
        <div className={styles.two}>
          <label className={styles.field}>Slot length (minutes)<input className={styles.input} inputMode="numeric" value={slot} onChange={(e) => setSlot(e.target.value)} /></label>
          <label className={styles.field}>Max patients (optional)<input className={styles.input} inputMode="numeric" value={cap} onChange={(e) => setCap(e.target.value)} placeholder="No cap" /></label>
        </div>
        <div className={styles.two}>
          <label className={styles.field}>Session type
            <select className={styles.input} value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="slot">Timed appointments</option>
              <option value="token">Tokens (numbered places)</option>
              <option value="hybrid">Appointments + walk-in tokens</option>
            </select>
          </label>
          <label className={styles.field}>Online booking cap (optional)<input className={styles.input} inputMode="numeric" value={onlineCap} onChange={(e) => setOnlineCap(e.target.value)} placeholder="Same as max" /></label>
        </div>
        <div className={styles.two}>
          <label className={styles.field}>Name (optional)<input className={styles.input} value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} placeholder="Evening OPD" /></label>
          <label className={styles.field}>Walk-ins after every
            <select className={styles.input} value={ratio} onChange={(e) => setRatio(e.target.value)}>
              <option value="2">2 booked patients</option><option value="3">3 booked patients</option><option value="4">4 booked patients</option>
            </select>
          </label>
        </div>
        {editing && mode !== editing.sessionMode && <p className={styles.note}><Icon name="info" size={12} /> A new session type applies from tomorrow; bookings already made stay as they are.</p>}
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

type LeaveSaved = { affected: string[]; leaveId: string | null; bookings: number };

/** ACC-006: the bookings the new leave covers, each with its nearest free times and a one-tap offer. Buttons only: no text input. */
function AffectedPanel({ leaveId, loadAffected, onOffer }: {
  leaveId: string;
  loadAffected: (leaveId: string) => Promise<AffectedBooking[]>;
  onOffer: (appointmentId: string) => Promise<string | null>;
}) {
  const [rows, setRows] = useState<AffectedBooking[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const load = () => loadAffected(leaveId).then(setRows, () => setError("Couldn't load the affected bookings."));
  useEffect(() => { void load(); }, [leaveId]); // eslint-disable-line react-hooks/exhaustive-deps
  if (error) return <p className={styles.error} role="alert"><Icon name="alert" size={14} /> {error}</p>;
  if (!rows) return <p className={styles.quiet}>Checking your bookings…</p>;
  if (rows.length === 0) return null;
  const offerable = rows.filter((r) => r.channelConsent && r.offerStatus !== "sent" && r.alternatives.length > 0);
  async function offer(ids: string[]) {
    setBusy(true);
    setError(null);
    for (const id of ids) { const err = await onOffer(id); if (err) { setError(err); break; } }
    await load();
    setBusy(false);
  }
  return (
    <div className={styles.warn} role="status">
      <Icon name="alert" size={14} />
      <div>
        <strong>{rows.length} {rows.length === 1 ? "booking" : "bookings"} affected</strong>
        <ul className={styles.affected}>
          {rows.map((r) => (
            <li key={r.appointmentId}>
              {formatClinicDate(r.start, { weekday: "short", day: "numeric", month: "short" })} {formatClinicTime(r.start)}
              {r.alternatives.length > 0 && <> · instead: {r.alternatives.map((a) => formatClinicTime(a)).join(", ")}</>}
              {" "}
              {r.offerStatus ? <span>Offer {r.offerStatus}</span>
                : !r.channelConsent ? <a className={styles.linkBtn} href={`/patients?open=${encodeURIComponent(r.patientId)}`}>Call patient</a>
                : <button type="button" className={styles.linkBtn} disabled={busy || r.alternatives.length === 0} onClick={() => void offer([r.appointmentId])}>Offer alternatives</button>}
            </li>
          ))}
        </ul>
        {offerable.length > 1 && <button type="button" className={styles.btnPrimary} disabled={busy} onClick={() => void offer(offerable.map((r) => r.appointmentId))}>Offer alternatives to all</button>}
      </div>
    </div>
  );
}

export function LeaveDialog({ onClose, onSave, loadAffected, onOffer }: {
  onClose: () => void;
  onSave: (i: LeaveInput) => Promise<{ error: string } | LeaveSaved>;
  loadAffected: (leaveId: string) => Promise<AffectedBooking[]>;
  onOffer: (appointmentId: string) => Promise<string | null>;
}) {
  const today = clinicToday();
  const [from, setFrom] = useState(today);
  const [to, setTo] = useState(today);
  const [part, setPart] = useState(false); // ACC-005: part of one clinic day
  const [winStart, setWinStart] = useState("14:00");
  const [winEnd, setWinEnd] = useState("17:00");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<LeaveSaved | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!from || !to) return setError("Choose the first and last day of leave.");
    if (to < from) return setError("The last day cannot be before the first day.");
    if (part && (from !== to || !winStart || !winEnd || winStart === winEnd)) return setError("Part-day leave is one day, with an end different from the start.");
    setBusy(true);
    const r = await onSave({ dateFrom: from, dateTo: to, reason: reason.trim() || null, ...(part ? { startTime: winStart, endTime: winEnd } : {}) });
    setBusy(false);
    if ("error" in r) setError(r.error); else setSaved(r);
  }

  if (saved) {
    const affected = saved.affected;
    return (
      <Dialog title="Leave recorded" onClose={onClose}>
        <div className={styles.form}>
          <p className={styles.ok}><Icon name="check" size={14} /> Your leave is saved and takes effect immediately.</p>
          {saved.leaveId && saved.bookings > 0
            ? <AffectedPanel leaveId={saved.leaveId} loadAffected={loadAffected} onOffer={onOffer} />
            : <p className={styles.note}><Icon name="info" size={12} /> No existing bookings fall in this period.</p>}
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
        <label className={styles.field}><span><input type="checkbox" checked={part} onChange={(e) => { setPart(e.target.checked); if (e.target.checked) setTo(from); }} /> Only part of the day</span></label>
        {part && (
          <div className={styles.two}>
            <label className={styles.field}>From<input className={styles.input} type="time" value={winStart} onChange={(e) => setWinStart(e.target.value)} required /></label>
            <label className={styles.field}>To<input className={styles.input} type="time" value={winEnd} onChange={(e) => setWinEnd(e.target.value)} required /></label>
          </div>
        )}
        <label className={styles.field}>Reason (optional)<input className={styles.input} value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} /></label>
        <p className={styles.note}><Icon name="info" size={12} /> Leave is recorded directly, with no approval step, and removes you from booking {part ? "in that window" : "on those days"}.</p>
        {error && <p className={styles.error} role="alert"><Icon name="alert" size={14} /> {error}</p>}
        <div className={styles.formActions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={busy}>{busy ? "Saving…" : "Record leave"}</button>
        </div>
      </form>
    </Dialog>
  );
}
