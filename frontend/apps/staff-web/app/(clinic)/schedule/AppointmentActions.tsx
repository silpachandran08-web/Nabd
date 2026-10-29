"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import Dialog from "../../components/ui/Dialog";
import ReasonPicker from "../../components/ui/ReasonPicker";
import { reasonReady, type ReasonItem } from "../../lib/reasonPicker";
import styles from "./schedule.module.css";
import { clinicTime } from "./NewAppointmentDialog";
import { hijriLabel } from "../../lib/hijri";
import { api } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import type { AppointmentResponse, QueueEntryResponse } from "@nabd/api-client/generated";

type Target = { appointmentId: string; patientName: string; doctorId: string; doctorName: string; time: string; dateLabel: string };

/**
 * Cancel an appointment (POST /v1/appointments/{id}/cancel, queue:edit). A reason from the coded list (ACC-007) is mandatory,
 * with a note only for `other`; nothing is sent until one is picked and confirmed — cancelling affects the patient.
 * Any refusal is shown verbatim so a mismatch is never silent.
 */
export function CancelAppointmentDialog({ target, onClose, onDone }: {
  target: Target; onClose: () => void; onDone: () => void;
}) {
  const [reason, setReason] = useState<ReasonItem | null>(null);
  const [note, setNote] = useState("");
  const [saving, setSaving] = useState(false);
  const fe = useFormErrors("cancel", { reasonCode: "Cancellation reason", note: "Note" });
  // undefined = not cancelled yet; otherwise the label of the recorded reason.
  const [recorded, setRecorded] = useState<string | undefined>(undefined);
  const ready = reasonReady(reason, note);

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (!reason || !ready || saving) return;
    setSaving(true);
    fe.clear();
    try {
      await api.post<AppointmentResponse | undefined>(`/appointments/${target.appointmentId}/cancel`,
        reason.requiresNote ? { reasonCode: reason.code, note: note.trim() } : { reasonCode: reason.code });
      setRecorded(reason.label + (reason.requiresNote ? ` — ${note.trim()}` : ""));
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
        <ReasonPicker kind="cancel" name="cancel-reason" legend="Cancellation reason (required)" value={reason}
          onChange={(r) => { fe.clearField("reasonCode"); setReason(r); }} note={note} onNote={setNote} />
        <FieldError fe={fe} name="reasonCode" className={styles.note} />
        <p className={styles.note}>
          {reason ? "The slot is released once the appointment is cancelled." : "Cancelling affects the patient — one reason is mandatory and is recorded."}
        </p>
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Keep appointment</button>
          <button type="submit" className={styles.btnDanger} disabled={!ready || saving}>
            {saving ? "Cancelling…" : ready ? "Cancel appointment" : "Select a reason"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

/**
 * ACC-025: mark a never-arrived appointment as no-show (POST /v1/appointments/{id}/no-show, queue:edit). The reason is optional;
 * the server refuses an appointment that has arrived or is not yet due and the message is shown verbatim.
 */
export function NoShowDialog({ target, onClose, onDone }: { target: Target; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState<ReasonItem | null>(null);
  const [saving, setSaving] = useState(false);
  const fe = useFormErrors("noshow", { reasonCode: "Reason" });

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    fe.clear();
    try {
      await api.post(`/appointments/${target.appointmentId}/no-show`, reason ? { reasonCode: reason.code } : {});
      onDone();
    } catch (err) {
      fe.fail(err, "Couldn't mark the no-show.");
      setSaving(false);
    }
  }

  return (
    <Dialog title="Mark as no-show" onClose={onClose}>
      <form onSubmit={confirm}>
        <p className={styles.dialogSub}>{target.patientName} · {target.time} with {target.doctorName} · {target.dateLabel}</p>
        <ReasonPicker kind="no_show" name="noshow-reason" legend="Reason (optional)" value={reason} onChange={setReason} askNote={false} />
        <p className={styles.note}>The patients behind move up. You can undo this today.</p>
        <ErrorSummary fe={fe} className={styles.error} />
        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Keep as booked</button>
          <button type="submit" className={styles.btnDanger} disabled={saving}>{saving ? "Marking…" : "Mark no-show"}</button>
        </div>
      </form>
    </Dialog>
  );
}

/** ACC-025: one confirm for the two no-show follow-ups — undo (same clinic day) and late arrival (walk-in token linked to the booking). */
export function NoShowFollowUpDialog({ kind, target, onClose, onDone }: {
  kind: "undo" | "late"; target: Target; onClose: () => void; onDone: () => void;
}) {
  const [saving, setSaving] = useState(false);
  const [token, setToken] = useState<number | null>(null);
  const fe = useFormErrors("noshow-followup", {});
  const undo = kind === "undo";

  async function confirm() {
    if (saving) return;
    setSaving(true);
    fe.clear();
    try {
      if (undo) {
        await api.post(`/appointments/${target.appointmentId}/no-show/undo`, {});
        onDone();
      } else {
        const entry = await api.post<QueueEntryResponse | undefined>("/queue/late-arrival", { appointmentId: target.appointmentId });
        setToken(entry?.tokenNumber ?? 0);
      }
    } catch (err) {
      fe.fail(err, undo ? "Couldn't undo the no-show." : "Couldn't check the patient in.");
    } finally {
      setSaving(false);
    }
  }

  if (token !== null) return (
    <Dialog title="Checked in" onClose={onDone}>
      <p className={styles.dialogSub}>{target.patientName} · booked {target.time} with {target.doctorName}</p>
      <p className={styles.note}>Walk-in token {token ? `T-${token}` : "issued"}, linked to the original booking.</p>
      <div className={styles.actions}><button type="button" className={styles.btnPrimary} onClick={onDone}>Close</button></div>
    </Dialog>
  );

  return (
    <Dialog title={undo ? "Undo no-show" : "Late arrival"} onClose={onClose}>
      <p className={styles.dialogSub}>{target.patientName} · {target.time} with {target.doctorName} · {target.dateLabel}</p>
      <p className={styles.note}>{undo ? "The appointment goes back to booked, as it was." : "The patient arrived after being marked. They join today's queue with a walk-in token linked to this booking."}</p>
      <ErrorSummary fe={fe} className={styles.error} />
      <div className={styles.actions}>
        <button type="button" className={styles.btn} onClick={onClose}>Close</button>
        <button type="button" className={styles.btnPrimary} disabled={saving} onClick={() => void confirm()}>
          {saving ? "Working…" : undo ? "Undo no-show" : "Check in as walk-in"}
        </button>
      </div>
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
