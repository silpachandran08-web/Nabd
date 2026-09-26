"use client";

import { useEffect, useState } from "react";
import styles from "./schedule.module.css";

type Patient = { id: string; mrn: string; name: string; phone: string };
type Problem = { title: string; detail: string };

/** Clinic-local "HH:mm" for an instant — the clinic's clock, not the browser's. */
export function clinicTime(instant: string, timezone: string): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: timezone })
    .format(new Date(instant));
}

/**
 * New appointment (wireframe "appointment" overlay). Booking goes through the existing
 * POST /v1/appointments, which enforces double-booking, session caps and holidays — any refusal is
 * shown here verbatim. Times come from GET /v1/doctors/{id}/availability for the chosen date.
 */
export default function NewAppointmentDialog({ doctors, timezone, initialDoctorId, initialDate, initialSlot, authedFetch, onClose, onBooked }: {
  doctors: { id: string; name: string }[];
  timezone: string;
  initialDoctorId: string;
  initialDate: string;
  initialSlot: string | null;
  authedFetch: (path: string, init?: RequestInit) => Promise<Response | null>;
  onClose: () => void;
  onBooked: () => void;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Patient[]>([]);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [doctorId, setDoctorId] = useState(initialDoctorId);
  const [date, setDate] = useState(initialDate);
  const [slots, setSlots] = useState<string[] | null>(null);
  const [slot, setSlot] = useState<string>(initialSlot ?? "");
  const [followUp, setFollowUp] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Free slots for the chosen doctor and day; keeps a pre-selected slot only if it's still free.
  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (!doctorId || !date) return;
      setSlots(null);
      const res = await authedFetch(`/doctors/${doctorId}/availability?date=${date}`);
      if (cancelled || !res) return;
      const list: string[] = res.ok ? await res.json() : [];
      if (cancelled) return;
      setSlots(list);
      setSlot((current) => (list.includes(current) ? current : ""));
    });
    return () => { cancelled = true; };
  }, [doctorId, date, authedFetch]);

  // Patient search, debounced.
  useEffect(() => {
    const q = query.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) {
        if (!cancelled) setResults([]);
        return;
      }
      const res = await authedFetch(`/patients?q=${encodeURIComponent(q)}&limit=8`);
      if (cancelled || !res?.ok) return;
      const page: { data: Patient[] } = await res.json();
      if (!cancelled) setResults(page.data);
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, authedFetch]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function book(e: React.FormEvent) {
    e.preventDefault();
    if (!patient || !doctorId || !slot) return;
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch("/appointments", {
        method: "POST",
        body: JSON.stringify({ patientId: patient.id, doctorId, startTime: slot, isFollowUp: followUp }),
      });
      if (!res) return;
      if (!res.ok) {
        const p: Problem = await res.json().catch(() => ({ title: "Error", detail: "Couldn't book the appointment." }));
        setError(p.detail || p.title);
        return;
      }
      onBooked();
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={styles.overlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="new-appt-title"
        onClick={(e) => e.stopPropagation()} onSubmit={book}>
        <h2 id="new-appt-title" className={styles.dialogTitle}>New appointment</h2>
        <p className={styles.dialogSub}>Times are the clinic&apos;s local time.</p>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-patient">Patient</label>
          {patient ? (
            <button type="button" className={styles.resultOn} onClick={() => setPatient(null)}>
              {patient.name}
              <div className={styles.resultSub}>{patient.mrn} · {patient.phone} · change</div>
            </button>
          ) : (
            <>
              <input id="appt-patient" className={styles.input} value={query} autoFocus autoComplete="off"
                placeholder="Search by name or phone" onChange={(e) => setQuery(e.target.value)} />
              {results.length > 0 && (
                <div className={styles.results}>
                  {results.map((p) => (
                    <button type="button" key={p.id} className={styles.result} onClick={() => setPatient(p)}>
                      {p.name}
                      <div className={styles.resultSub}>{p.mrn} · {p.phone}</div>
                    </button>
                  ))}
                </div>
              )}
              {query.trim().length >= 2 && results.length === 0 && (
                <div className={styles.resultSub}>No matching patients — register them from Live Queue first.</div>
              )}
            </>
          )}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-doctor">Doctor</label>
          <select id="appt-doctor" className={styles.input} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
            {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-date">Date</label>
          <input id="appt-date" type="date" className={styles.input} value={date} onChange={(e) => setDate(e.target.value)} />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-time">Time</label>
          <select id="appt-time" className={styles.input} value={slot} onChange={(e) => setSlot(e.target.value)} disabled={!slots || slots.length === 0}>
            <option value="">{slots === null ? "Loading free slots…" : slots.length === 0 ? "No free slots on this day" : "Choose a time"}</option>
            {(slots ?? []).map((s) => <option key={s} value={s}>{clinicTime(s, timezone)}</option>)}
          </select>
        </div>

        <label className={styles.checkRow}>
          <input type="checkbox" checked={followUp} onChange={(e) => setFollowUp(e.target.checked)} /> Follow-up visit
        </label>

        {error && <div className={styles.error} role="alert">{error}</div>}

        <div className={styles.actions}>
          <button type="button" className={styles.btn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.btnPrimary} disabled={saving || !patient || !slot}>
            {saving ? "Booking…" : "Book appointment"}
          </button>
        </div>
      </form>
    </div>
  );
}
