"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Dialog from "../components/ui/Dialog";
import styles from "./schedule.module.css";
import { clinicTime } from "./NewAppointmentDialog";
import { hijriLabel } from "../lib/hijri";
import { api } from "../lib/api";
import { getter, key } from "../lib/query";
import { ErrorSummary, FieldError, useFormErrors } from "../lib/formErrors";
import type { AppointmentResponse } from "@nabd/api-client/generated";

// Wireframe CANCEL_REASONS (NabdShell.html L5906). The chosen label is what gets sent.
export const CANCEL_REASONS = [
  "Schedule conflict", "Illness", "Cost", "Transport", "Patient went elsewhere", "Clinic initiated", "Other",
];

type Target = { appointmentId: string; patientName: string; doctorId: string; doctorName: string; time: string; dateLabel: string };

/**
 * Cancel an appointment (POST /v1/appointments/{id}/cancel, queue:edit). The reason is mandatory
 * and nothing is sent until one is picked and confirmed — cancelling affects the patient.
 * Body `reason` (CancelRequest, min 3 chars); any refusal is
 * shown verbatim so a mismatch is never silent.
 */
export function CancelAppointmentDialog({ target, onClose, onDone }: {
  target: Target; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = useState("");
  const [saving, setSaving] = useState(false);
  const fe = useFormErrors("cancel", { reason: "Cancellation reason" });
  // undefined = not cancelled yet; otherwise the server's recorded cancelReason (QA-06), null when absent.
  const [recorded, setRecorded] = useState<string | null | undefined>(undefined);

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (!reason || saving) return;
    setSaving(true);
    fe.clear();
    try {
      const body = await api.post<AppointmentResponse | undefined>(`/appointments/${target.appointmentId}/cancel`, { reason });
      setRecorded(body?.cancelReason ?? null);
    } catch (err) {
      fe.fail(err, "Couldn't cancel the appointment.");
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
              <input type="radio" name="cancel-reason" value={r} checked={reason === r} {...fe.attrs("reason")} onChange={() => { fe.clearField("reason"); setReason(r); }} /> {r}
            </label>
          ))}
        </fieldset>
        <FieldError fe={fe} name="reason" className={styles.note} />
        <p className={styles.note}>
          {reason ? "The slot is released once the appointment is cancelled." : "Cancelling affects the patient — one reason is mandatory and is recorded."}
        </p>
        <ErrorSummary fe={fe} className={styles.error} />
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
export function MoveAppointmentDialog({ target, timezone, initialDate, onClose, onDone }: {
  target: Target; timezone: string; initialDate: string; onClose: () => void; onDone: () => void;
}) {
  const [date, setDate] = useState(initialDate);
  const [picked, setSlot] = useState("");
  const [saving, setSaving] = useState(false);
  const fe = useFormErrors("move", { newStartTime: "New time" });
  const slotsQ = useQuery({
    queryKey: key("availability", { doctorId: target.doctorId, date }),
    queryFn: getter<string[]>(`/doctors/${target.doctorId}/availability?date=${date}`),
    enabled: !!date, staleTime: 0, gcTime: 0,
  });
  const loadError = slotsQ.isError;
  const slots = slotsQ.data ?? (loadError ? [] : null);
  const slot = slots?.includes(picked) ? picked : "";

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (!slot || saving) return;
    setSaving(true);
    fe.clear();
    try {
      await api.post(`/appointments/${target.appointmentId}/reschedule`, { newStartTime: slot });
      onDone();
    } catch (err) {
      fe.fail(err, "Couldn't move the appointment.");
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
          <select id="move-time" className={styles.input} value={slot} {...fe.attrs("newStartTime")} onChange={(e) => { fe.clearField("newStartTime"); setSlot(e.target.value); }} disabled={!slots || slots.length === 0}>
            <option value="">{slots === null ? "Loading free slots…" : slots.length === 0 ? "No free slots on this day" : "Choose a time"}</option>
            {(slots ?? []).map((s) => <option key={s} value={s}>{clinicTime(s, timezone)}</option>)}
          </select>
          <FieldError fe={fe} name="newStartTime" className={styles.note} />
        </div>
        {loadError && <div className={styles.error} role="alert">Couldn&apos;t load free slots. Pick the date again to retry.</div>}
        <p className={styles.note}>On confirm the old slot is released and the appointment moves to the chosen time. Times are the clinic&apos;s local time.</p>
        <ErrorSummary fe={fe} className={styles.error} />
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
