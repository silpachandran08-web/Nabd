"use client";

import { useEffect, useState } from "react";
import Dialog from "../components/ui/Dialog";
import styles from "./schedule.module.css";
import { clinicTime } from "./NewAppointmentDialog";
import { hijriLabel } from "../lib/hijri";

type Problem = { title: string; detail: string };
type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

// Wireframe CANCEL_REASONS (NabdShell.html L5906). The chosen label is what gets sent.
export const CANCEL_REASONS = [
  "Schedule conflict", "Illness", "Cost", "Transport", "Patient went elsewhere", "Clinic initiated", "Other",
];

async function problemText(res: Response, fallback: string): Promise<string> {
  const p: Problem | null = await res.json().catch(() => null);
  return p?.detail || p?.title || fallback;
}

type Target = { appointmentId: string; patientName: string; doctorId: string; doctorName: string; time: string; dateLabel: string };

/**
 * Cancel an appointment (POST /v1/appointments/{id}/cancel, queue:edit). The reason is mandatory
 * and nothing is sent until one is picked and confirmed — cancelling affects the patient.
 * Body `reason` (CancelRequest, min 3 chars); any refusal is
 * shown verbatim so a mismatch is never silent.
 */
export function CancelAppointmentDialog({ target, authedFetch, onClose, onDone }: {
  target: Target; authedFetch: AuthedFetch; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // undefined = not cancelled yet; otherwise the server's recorded cancelReason (QA-06), null when absent.
  const [recorded, setRecorded] = useState<string | null | undefined>(undefined);

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (!reason || saving) return;
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch(`/appointments/${target.appointmentId}/cancel`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      });
      if (!res) return;
      if (!res.ok) {
        setError(await problemText(res, "Couldn't cancel the appointment."));
        return;
      }
      const body: { cancelReason?: string | null } = await res.json().catch(() => ({}));
      setRecorded(body.cancelReason ?? null);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  if (recorded !== undefined) return (
    <Dialog title="Appointment cancelled" onClose={onDone}>
      <p className={styles.dialogSub}>{target.patientName} · {target.time} with {target.doctorName} · {target.dateLabel}</p>
      <p className={styles.note}>Cancellation reason: {recorded || "—"}</p>
      <div className={styles.actions}>
        <button type="button" className={styles.btn} onClick={onDone}>Close</button>
      </div>
    </Dialog>
  );

  return (
    <Dialog title="Cancel appointment" onClose={onClose}>
      <form onSubmit={confirm}>
        <p className={styles.dialogSub}>{target.patientName} · {target.time} with {target.doctorName} · {target.dateLabel}</p>
        <fieldset className={styles.reasonSet}>
          <legend className={styles.label}>Cancellation reason (required)</legend>
          {CANCEL_REASONS.map((r) => (
            <label key={r} className={reason === r ? styles.reasonOn : styles.reason}>
              <input type="radio" name="cancel-reason" value={r} checked={reason === r} onChange={() => setReason(r)} /> {r}
            </label>
          ))}
        </fieldset>
        <p className={styles.note}>
          {reason ? "The slot is released once the appointment is cancelled." : "Cancelling affects the patient — one reason is mandatory and is recorded."}
        </p>
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Keep appointment</button>
          <button type="submit" className={styles.btnDanger} disabled={!reason || saving}>
            {saving ? "Cancelling…" : reason ? "Cancel appointment" : "Select a reason"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/**
 * Move an appointment to another free slot of the same doctor (POST /v1/appointments/{id}/reschedule,
 * queue:edit). Free slots come from GET /v1/doctors/{id}/availability. The wireframe's move reason
 * and patient notice have no API, so they are not offered. Body field `newStartTime` (RescheduleRequest) —
 * refusals are shown verbatim.
 */
export function MoveAppointmentDialog({ target, timezone, initialDate, authedFetch, onClose, onDone }: {
  target: Target; timezone: string; initialDate: string; authedFetch: AuthedFetch; onClose: () => void; onDone: () => void;
}) {
  const [date, setDate] = useState(initialDate);
  const [slots, setSlots] = useState<string[] | null>(null);
  const [slot, setSlot] = useState("");
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (!date) return;
      setSlots(null);
      setSlot("");
      setLoadError(false);
      try {
        const res = await authedFetch(`/doctors/${target.doctorId}/availability?date=${date}`);
        if (cancelled || !res) return;
        if (!res.ok) { setLoadError(true); setSlots([]); return; }
        const list: string[] = await res.json();
        if (!cancelled) setSlots(list);
      } catch {
        if (!cancelled) { setLoadError(true); setSlots([]); }
      }
    });
    return () => { cancelled = true; };
  }, [date, target.doctorId, authedFetch]);

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (!slot || saving) return;
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch(`/appointments/${target.appointmentId}/reschedule`, {
        method: "POST",
        body: JSON.stringify({ newStartTime: slot }),
      });
      if (!res) return;
      if (!res.ok) {
        setError(await problemText(res, "Couldn't move the appointment."));
        return;
      }
      onDone();
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog title="Move appointment" onClose={onClose}>
      <form onSubmit={confirm}>
        <p className={styles.dialogSub}>{target.patientName} · from {target.time} with {target.doctorName} · {target.dateLabel}</p>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="move-date">New date</label>
          <input id="move-date" type="date" className={styles.input} value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
          {date && <span className={styles.hijri} data-testid="hijri-date">{hijriLabel(date)}</span>}
        </div>
        <div className={styles.field}>
          <label className={styles.label} htmlFor="move-time">Available slots</label>
          <select id="move-time" className={styles.input} value={slot} onChange={(e) => setSlot(e.target.value)} disabled={!slots || slots.length === 0}>
            <option value="">{slots === null ? "Loading free slots…" : slots.length === 0 ? "No free slots on this day" : "Choose a time"}</option>
            {(slots ?? []).map((s) => <option key={s} value={s}>{clinicTime(s, timezone)}</option>)}
          </select>
        </div>
        {loadError && <div className={styles.error} role="alert">Couldn&apos;t load free slots. Pick the date again to retry.</div>}
        <p className={styles.note}>On confirm the old slot is released and the appointment moves to the chosen time. Times are the clinic&apos;s local time.</p>
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Close</button>
          <button type="submit" className={styles.btnPrimary} disabled={!slot || saving}>
            {saving ? "Moving…" : slot ? "Move appointment" : "Pick a slot"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
