"use client";

import { useCallback, useEffect, useState } from "react";
import Dialog from "../ui/Dialog";
import { Skeleton } from "../ui/StateViews";
import styles from "./checkin.module.css";

// One check-in overlay shared by Arrivals and Patients (REC-041, REC-017): confirm -> POST
// /v1/queue/check-in -> result slip with token, queue position, estimated wait and print.

export type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;

type QueueEntryLite = { id?: string; doctorId?: string; tokenNumber: number; priority?: boolean; status?: string; createdAt?: string };

export type CheckInResult = { tokenNumber: number; patientName: string; doctorId: string; doctorName: string; entryId?: string };

/** Maps a failed response to something the front desk can act on (a bare 500 says nothing). */
export async function problemMessage(res: Response, fallback: string): Promise<string> {
  if (res.status >= 500) return "The server had a problem and couldn't finish this. Try again in a moment; if it keeps happening, tell your clinic admin.";
  if (res.status === 403) return "Your role isn't allowed to do this.";
  const p: { title?: string; detail?: string } | null = await res.json().catch(() => null);
  return p?.detail || p?.title || fallback;
}

const WAITING = ["checked_in", "waiting", "billing_pending", "vitals_pending", "vitals_done"];

/** Token slip shown after any successful check-in. Position and wait are best-effort reads. */
export function CheckInResultDialog({ result, authedFetch, onClose }: { result: CheckInResult; authedFetch: AuthedFetch; onClose: () => void }) {
  const [position, setPosition] = useState<number | null | undefined>(undefined);
  const [waitMin, setWaitMin] = useState<number | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      try {
        const [qRes, wRes] = await Promise.all([
          authedFetch("/queue"),
          authedFetch(`/queue/wait-estimate?doctorId=${encodeURIComponent(result.doctorId)}`),
        ]);
        if (cancelled) return;
        if (qRes?.ok) {
          const list: QueueEntryLite[] = await qRes.json();
          const ahead = list
            .filter((e) => e.doctorId === result.doctorId && WAITING.includes(e.status ?? "waiting"))
            .sort((a, b) => Number(!!b.priority) - Number(!!a.priority) || new Date(a.createdAt ?? 0).getTime() - new Date(b.createdAt ?? 0).getTime());
          const idx = ahead.findIndex((e) => (result.entryId ? e.id === result.entryId : e.tokenNumber === result.tokenNumber));
          if (!cancelled) setPosition(idx >= 0 ? idx + 1 : null);
        } else setPosition(null);
        if (wRes?.ok) {
          const w: { estimatedMinutes?: number } = await wRes.json();
          if (!cancelled) setWaitMin(typeof w.estimatedMinutes === "number" ? w.estimatedMinutes : null);
        } else setWaitMin(null);
      } catch {
        if (!cancelled) { setPosition(null); setWaitMin(null); }
      }
    });
    return () => { cancelled = true; };
  }, [authedFetch, result]);

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

export type CheckInPatient = {
  id: string; name: string; nextAppointmentToday: boolean; nextAppointmentId: string | null;
  nextDoctorId: string | null; nextDoctorName: string | null; nextAppointment: string | null;
};

/**
 * Check a known patient in. With a booking today it checks in against it (appointmentId);
 * otherwise it's a walk-in for the chosen doctor. `onCheckedIn` fires on success (refresh the
 * list); `onDone` fires when the token slip is dismissed (close the dialog).
 */
export function CheckInDialog({ patient, authedFetch, onClose, onDone, onCheckedIn }: {
  patient: CheckInPatient; authedFetch: AuthedFetch; onClose: () => void;
  onDone: (token: number) => void; onCheckedIn?: (token: number) => void;
}) {
  const booked = !!(patient.nextAppointmentToday && patient.nextAppointmentId && patient.nextDoctorId);
  const [doctors, setDoctors] = useState<{ id: string; name: string }[] | null>(null);
  const [doctorId, setDoctorId] = useState(patient.nextDoctorId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<CheckInResult | null>(null);

  useEffect(() => {
    if (booked) return;
    let cancelled = false;
    void Promise.resolve().then(async () => {
      const res = await authedFetch("/staff/roster");
      if (cancelled || !res) return;
      const list: { id: string; name: string }[] = res.ok ? await res.json() : [];
      if (!cancelled) setDoctors(list);
    });
    return () => { cancelled = true; };
  }, [booked, authedFetch]);

  const finish = useCallback(() => { if (result) onDone(result.tokenNumber); }, [result, onDone]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const body = booked
        ? { patientId: patient.id, doctorId: patient.nextDoctorId, appointmentId: patient.nextAppointmentId }
        : { patientId: patient.id, doctorId };
      const res = await authedFetch("/queue/check-in", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (!res.ok) {
        setError(await problemMessage(res, "Couldn't check the patient in."));
        return;
      }
      const entry: QueueEntryLite = await res.json();
      const dId = (booked ? patient.nextDoctorId : doctorId) ?? "";
      const dName = booked ? patient.nextDoctorName ?? "" : doctors?.find((d) => d.id === dId)?.name ?? "";
      setResult({ tokenNumber: entry.tokenNumber, patientName: patient.name, doctorId: dId, doctorName: dName, entryId: entry.id });
      onCheckedIn?.(entry.tokenNumber);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  if (result) return <CheckInResultDialog result={result} authedFetch={authedFetch} onClose={finish} />;

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
            <label className={styles.label} htmlFor="checkin-doctor">Doctor</label>
            <select id="checkin-doctor" className={styles.select} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
              <option value="">{doctors === null ? "Loading…" : "Choose a doctor"}</option>
              {(doctors ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </>
        )}
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
