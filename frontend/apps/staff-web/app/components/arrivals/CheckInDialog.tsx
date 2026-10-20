"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { Skeleton } from "../ui/StateViews";
import { PAYMENT_METHODS, paymentMethodLabel } from "../checkout/paymentMethods";
import { getIdentity } from "../../lib/session";
import { api, errorText, intentKeys } from "../../lib/api";
import type { QueueEntry, StaffOption, WaitEstimate } from "@nabd/api-client/types";
import styles from "./checkin.module.css";

// One check-in overlay shared by Arrivals and Patients (REC-041, REC-017): confirm -> POST
// /v1/queue/check-in -> result slip with token, queue position, estimated wait and print.


export type CheckInResult = { tokenNumber: number; patientName: string; doctorId: string; doctorName: string; entryId?: string };

const WAITING = ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"];

/** Token slip shown after any successful check-in. Position and wait are best-effort reads. */
export function CheckInResultDialog({ result, onClose }: { result: CheckInResult; onClose: () => void }) {
  const [position, setPosition] = useState<number | null | undefined>(undefined);
  const [waitMin, setWaitMin] = useState<number | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      // Best-effort reads: each one that fails leaves its fact as "—".
      const [list, wait] = await Promise.all([
        api.get<QueueEntry[]>("/queue").catch(() => null),
        api.get<WaitEstimate>(`/queue/wait-estimate?doctorId=${encodeURIComponent(result.doctorId)}`).catch(() => null),
      ]);
      if (cancelled) return;
      if (list) {
        const ahead = list
          .filter((e) => e.doctorId === result.doctorId && WAITING.includes(e.status ?? "waiting"))
          .sort((a, b) => Number(!!b.priority) - Number(!!a.priority) || new Date(a.createdAt ?? 0).getTime() - new Date(b.createdAt ?? 0).getTime());
        const idx = ahead.findIndex((e) => (result.entryId ? e.id === result.entryId : e.tokenNumber === result.tokenNumber));
        setPosition(idx >= 0 ? idx + 1 : null);
      } else setPosition(null);
      setWaitMin(typeof wait?.estimatedMinutes === "number" ? wait.estimatedMinutes : null);
    });
    return () => { cancelled = true; };
  }, [result]);

  const loading = position === undefined || waitMin === undefined;
  return (
    <Dialog title="Checked in" onClose={onClose}>
      <div className={`${styles.result} ${styles.printArea}`}>
        <div className={styles.tokenLabel}>Token</div>
        <div className={styles.tokenNumber}>#{result.tokenNumber}</div>
        <div className={styles.patient}>{result.patientName}</div>
        {loading ? <Skeleton rows={1} label="Loading queue position" /> : (
          <dl className={styles.facts}>
            <div><dt>Doctor</dt><dd>{result.doctorName || "—"}</dd></div>
            <div><dt>Queue position</dt><dd>{position ? `#${position}` : "—"}</dd></div>
            <div><dt>Estimated wait</dt><dd>{waitMin != null ? `~${waitMin} min` : "—"}</dd></div>
            <div><dt>Time</dt><dd>{new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</dd></div>
          </dl>
        )}
        {!loading && (position == null || waitMin == null) && (
          <p className={`${styles.note} ${styles.noPrint}`}>Position or wait time isn&apos;t available right now.</p>
        )}
      </div>
      <div className={`${styles.actions} ${styles.noPrint}`}>
        <button type="button" className={styles.secondaryBtn} onClick={() => window.print()}>Print token</button>
        <button type="button" className={styles.primaryBtn} onClick={onClose}>Done</button>
      </div>
    </Dialog>
  );
}

/** Visit intake captured at every check-in (CheckInRequest.presentingComplaint / paymentMode), shared by
 * this dialog and the Arrivals walk-in drawer so both send the same fields. */
export function CheckInIntakeFields({ complaint, onComplaint, paymentMode, onPaymentMode, region }: {
  complaint: string; onComplaint: (v: string) => void; paymentMode: string; onPaymentMode: (v: string) => void; region?: string | null;
}) {
  return (
    <>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="checkin-payment">Payment mode</label>
        <select id="checkin-payment" className={styles.select} value={paymentMode} onChange={(e) => onPaymentMode(e.target.value)}>
          {PAYMENT_METHODS.map((m) => <option key={m} value={m}>{paymentMethodLabel(m, region)}</option>)}
        </select>
      </div>
      <div className={styles.field}>
        <label className={styles.label} htmlFor="checkin-complaint">Presenting complaint</label>
        <textarea id="checkin-complaint" className={styles.textarea} value={complaint}
          onChange={(e) => onComplaint(e.target.value)} placeholder="Fever, 3 days" />
      </div>
    </>
  );
}

export type CheckInPatient = {
  id: string; name: string; nextAppointmentToday: boolean; nextAppointmentId: string | null;
  nextDoctorId: string | null; nextDoctorName: string | null; nextAppointment: string | null;
};

/**
 * Check a known patient in. With a booking today it checks in against it (appointmentId);
 * otherwise it's a walk-in for the chosen doctor. `onCheckedIn` fires on success (refresh the
 * list); `onDone` fires when the token slip is dismissed (close the dialog).
 */
export function CheckInDialog({ patient, onClose, onDone, onCheckedIn }: {
  patient: CheckInPatient; onClose: () => void;
  onDone: (token: number) => void; onCheckedIn?: (token: number) => void;
}) {
  const booked = !!(patient.nextAppointmentToday && patient.nextAppointmentId && patient.nextDoctorId);
  const [doctors, setDoctors] = useState<StaffOption[] | null>(null);
  const [doctorId, setDoctorId] = useState(patient.nextDoctorId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<CheckInResult | null>(null);
  const [complaint, setComplaint] = useState("");
  const [paymentMode, setPaymentMode] = useState("cash");
  const [region] = useState(() => getIdentity()?.tenantRegion);
  const keys = useRef(intentKeys()); // one Idempotency-Key per check-in intent, reused when the same request is re-sent

  useEffect(() => {
    if (booked) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      const list = await api.get<StaffOption[]>("/staff/roster").catch(() => []);
      if (!cancelled) setDoctors(list);
    });
    return () => { cancelled = true; };
  }, [booked]);

  const finish = useCallback(() => { if (result) onDone(result.tokenNumber); }, [result, onDone]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const intake = { presentingComplaint: complaint.trim() || null, paymentMode };
      const body = booked
        ? { patientId: patient.id, doctorId: patient.nextDoctorId, appointmentId: patient.nextAppointmentId, ...intake }
        : { patientId: patient.id, doctorId, ...intake };
      const entry = await api.post<QueueEntry>("/queue/check-in", body, { idempotencyKey: keys.current.get("checkin", body) });
      keys.current.done("checkin");
      const dId = (booked ? patient.nextDoctorId : doctorId) ?? "";
      const dName = booked ? patient.nextDoctorName ?? "" : doctors?.find((d) => d.id === dId)?.name ?? "";
      setResult({ tokenNumber: entry.tokenNumber, patientName: patient.name, doctorId: dId, doctorName: dName, entryId: entry.id });
      onCheckedIn?.(entry.tokenNumber);
    } catch (e) {
      setError(errorText(e, "Couldn't check the patient in."));
    } finally {
      setSaving(false);
    }
  }

  if (result) return <CheckInResultDialog result={result} onClose={finish} />;

  const time = patient.nextAppointment
    ? new Date(patient.nextAppointment).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "";
  return (
    <Dialog title={`Check in ${patient.name}`} onClose={onClose}>
      <form onSubmit={submit}>
        {booked ? (
          <p className={styles.sub}>Today&apos;s {time} appointment with {patient.nextDoctorName}.</p>
        ) : (
          <>
            <p className={styles.sub}>No booking today — this checks them in as a walk-in.</p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="checkin-doctor">Doctor</label>
              <select id="checkin-doctor" className={styles.select} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
                <option value="">{doctors === null ? "Loading…" : "Choose a doctor"}</option>
                {(doctors ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </div>
          </>
        )}
        <CheckInIntakeFields complaint={complaint} onComplaint={setComplaint} paymentMode={paymentMode} onPaymentMode={setPaymentMode} region={region} />
        {error && <div className={styles.error} role="alert">{error}</div>}
        <div className={styles.actions}>
          <button type="button" className={styles.secondaryBtn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.primaryBtn} disabled={saving || (!booked && !doctorId)}>
            {saving ? "Checking in…" : "Check in"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
