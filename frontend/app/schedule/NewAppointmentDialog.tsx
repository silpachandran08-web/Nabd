"use client";

import { useEffect, useState } from "react";
import { can } from "../lib/permissions";
import styles from "./schedule.module.css";
import { hijriLabel } from "../lib/hijri";

type Patient = { id: string; mrn: string; name: string; phone: string };
type Problem = { type?: string; title?: string; detail?: string };
type Candidate = { patientId: string; name: string; phone: string; matchScore: number };

/** Plain-language booking refusals keyed by the problem type slug; anything unknown gets the generic line, never the raw server text. */
function bookingMessage(status: number, p: Problem | null): string {
  const slug = p?.type?.split("/").pop();
  if (slug === "slot-unavailable") return "That time was just taken by another booking.";
  if (slug === "session-full") return "This doctor's session is full.";
  if (slug === "slot-in-past") return "That time has already passed. Choose a later time.";
  if (slug === "clinic-holiday") return p?.detail || "The clinic is closed on that day.";
  if (slug === "patient-not-found" || slug === "doctor-not-found") return "That patient or doctor can no longer be found. Search again.";
  if (status === 403) return "You don't have permission to book appointments.";
  if (status >= 500) return "Something went wrong on our side. Try again in a moment.";
  return "Couldn't book the appointment. Check the details and try again.";
}

/** Clinic-local "HH:mm" for an instant — the clinic's clock, not the browser's. */
export function clinicTime(instant: string, timezone: string): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: timezone })
    .format(new Date(instant));
}

/**
 * New appointment (wireframe "appointment" overlay). Booking goes through the existing
 * POST /v1/appointments, which enforces double-booking, session caps and holidays — refusals are
 * shown in plain language (REC-029). Times come from GET /v1/doctors/{id}/availability for the chosen date.
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
  const [reload, setReload] = useState(0); // bump to refetch free slots (retry / after a conflict)
  const [slotsFailed, setSlotsFailed] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const [conflictSlot, setConflictSlot] = useState<string | null>(null); // the slot a refusal was about; drives "nearest free times"
  // Quick-create patient (patients:create). shortcut: no guardian/consent flow, under-18s go to Arrivals; no "different person" override, upgrade with REC patient-register work.
  const canRegister = can("patients:create");
  const [registering, setRegistering] = useState(false);
  const [np, setNp] = useState({ name: "", phone: "", dob: "", gender: "male" });
  const [dupes, setDupes] = useState<Candidate[]>([]);

  // Free slots for the chosen doctor and day; keeps a pre-selected slot only if it's still free.
  useEffect(() => {
    let cancelled = false;
    void Promise.resolve().then(async () => {
      if (!doctorId || !date) return;
      setSlots(null);
      setSlotsFailed(false);
      const res = await authedFetch(`/doctors/${doctorId}/availability?date=${date}`).catch(() => null);
      if (cancelled || !res) return;
      const list: string[] = res.ok ? await res.json() : [];
      if (cancelled) return;
      setSlotsFailed(!res.ok);
      setSlots(list);
      setSlot((current) => (list.includes(current) ? current : ""));
    });
    return () => { cancelled = true; };
  }, [doctorId, date, authedFetch, reload]);

  // Patient search, debounced.
  useEffect(() => {
    const q = query.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) {
        if (!cancelled) setResults([]);
        return;
      }
      const res = await authedFetch(`/patients?q=${encodeURIComponent(q)}&limit=8`).catch(() => null);
      if (cancelled || !res) return;
      setSearchFailed(!res.ok);
      if (!res.ok) { setResults([]); return; }
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
        const p: Problem | null = await res.json().catch(() => null);
        setError(bookingMessage(res.status, p));
        const slug = p?.type?.split("/").pop();
        if (slug === "slot-unavailable" || slug === "session-full") { setConflictSlot(slot); setReload((n) => n + 1); }
        return;
      }
      onBooked();
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  async function registerPatient(e: React.FormEvent) {
    e.preventDefault();
    const age = (Date.now() - new Date(np.dob).getTime()) / 31557600000;
    if (age < 18) { setError("Patients under 18 need a guardian. Register them from Arrivals, then book here."); return; }
    setSaving(true);
    setError(null);
    try {
      const res = await authedFetch("/patients", { method: "POST", body: JSON.stringify(np) });
      if (!res) return;
      if (res.status === 409) {
        const body: { candidates?: Candidate[] } = await res.json().catch(() => ({}));
        setDupes([...(body.candidates ?? [])].sort((x, y) => y.matchScore - x.matchScore));
        setError("A patient with these details may already exist. Choose them below instead of registering again.");
        return;
      }
      if (!res.ok) { setError(res.status === 403 ? "You don't have permission to register patients." : "Couldn't register the patient. Check the details and try again."); return; }
      setPatient(await res.json());
      setRegistering(false);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  // Nearest other free times for the same doctor/day after a refusal. shortcut: same doctor and day only, upgrade to other doctors/days if the desk asks.
  const alternatives = conflictSlot && slots
    ? slots.filter((s) => s !== conflictSlot)
        .sort((a, b) => Math.abs(+new Date(a) - +new Date(conflictSlot)) - Math.abs(+new Date(b) - +new Date(conflictSlot))).slice(0, 3)
    : [];

  return (
    <div className={styles.overlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="new-appt-title"
        onClick={(e) => e.stopPropagation()} onSubmit={registering ? registerPatient : book}>
        <h2 id="new-appt-title" className={styles.dialogTitle}>New appointment</h2>
        <p className={styles.dialogSub}>Times are the clinic&apos;s local time.</p>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-patient">Patient</label>
          {patient ? (
            <button type="button" className={`${styles.resultOn} ${styles.tap}`} onClick={() => setPatient(null)}>
              {patient.name}
              <div className={styles.resultSub}>{patient.mrn} · {patient.phone} · change</div>
            </button>
          ) : (
            <>
              <input id="appt-patient" className={`${styles.input} ${styles.tap}`} value={query} autoFocus autoComplete="off"
                placeholder="Search by name or phone" onChange={(e) => setQuery(e.target.value)} />
              {results.length > 0 && (
                <div className={styles.results}>
                  {results.map((p) => (
                    <button type="button" key={p.id} className={`${styles.result} ${styles.tap}`} onClick={() => setPatient(p)}>
                      {p.name}
                      <div className={styles.resultSub}>{p.mrn} · {p.phone}</div>
                    </button>
                  ))}
                </div>
              )}
              {searchFailed && <div className={styles.resultSub} role="alert">Couldn&apos;t search patients. Check your connection and keep typing to retry.</div>}
              {!searchFailed && query.trim().length >= 2 && results.length === 0 && !registering && (
                <div className={styles.resultSub}>
                  No matching patients.{" "}
                  {canRegister
                    ? <button type="button" className={`${styles.btn} ${styles.tap}`} onClick={() => setRegistering(true)}>Register new patient</button>
                    : "Ask a colleague with registration access to add them, then search again."}
                </div>
              )}
              {registering && (
                <fieldset className={styles.quickPatient}>
                  <legend className={styles.label}>New patient</legend>
                  <input aria-label="Patient name" className={`${styles.input} ${styles.tap}`} required placeholder="Full name" value={np.name} onChange={(e) => setNp({ ...np, name: e.target.value })} />
                  <input aria-label="Patient phone" className={`${styles.input} ${styles.tap}`} required type="tel" placeholder="Phone" value={np.phone} onChange={(e) => setNp({ ...np, phone: e.target.value })} />
                  <input aria-label="Date of birth" className={`${styles.input} ${styles.tap}`} required type="date" value={np.dob} onChange={(e) => setNp({ ...np, dob: e.target.value })} />
                  <select aria-label="Sex" className={`${styles.input} ${styles.tap}`} value={np.gender} onChange={(e) => setNp({ ...np, gender: e.target.value })}>
                    <option value="male">Male</option><option value="female">Female</option><option value="other">Other</option>
                  </select>
                  {dupes.map((c) => (
                    <button type="button" key={c.patientId} className={`${styles.result} ${styles.tap}`} onClick={() => { setPatient({ id: c.patientId, name: c.name, phone: c.phone, mrn: "Existing record" }); setRegistering(false); setDupes([]); setError(null); }}>
                      Use existing: {c.name}
                      <div className={styles.resultSub}>{c.phone}</div>
                    </button>
                  ))}
                  <button type="submit" className={`${styles.btnPrimary} ${styles.tap}`} disabled={saving || !np.name.trim() || !np.phone.trim() || !np.dob}>
                    {saving ? "Saving…" : "Save patient"}
                  </button>
                </fieldset>
              )}
            </>
          )}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-doctor">Doctor</label>
          <select id="appt-doctor" className={`${styles.input} ${styles.tap}`} value={doctorId} onChange={(e) => setDoctorId(e.target.value)}>
            {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-date">Date</label>
          <input id="appt-date" type="date" className={`${styles.input} ${styles.tap}`} value={date} onChange={(e) => setDate(e.target.value)} />
          {date && <span className={styles.hijri} data-testid="hijri-date">{hijriLabel(date)}</span>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-time">Time</label>
          <select id="appt-time" className={`${styles.input} ${styles.tap}`} value={slot} onChange={(e) => { setSlot(e.target.value); setConflictSlot(null); }} disabled={!slots || slots.length === 0}>
            <option value="">{slots === null ? "Loading free slots…" : slotsFailed ? "Couldn't load free slots" : slots.length === 0 ? "No free slots on this day" : "Choose a time"}</option>
            {(slots ?? []).map((s) => <option key={s} value={s}>{clinicTime(s, timezone)}</option>)}
          </select>
          {slotsFailed && <button type="button" className={`${styles.btn} ${styles.tap}`} onClick={() => setReload((n) => n + 1)}>Retry</button>}
          {slots && slots.length === 0 && !slotsFailed && <div className={styles.resultSub}>Try another date or doctor.</div>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-visit">Visit type</label>
          <select id="appt-visit" className={`${styles.input} ${styles.tap}`} value={followUp ? "follow_up" : "appointment"} onChange={(e) => setFollowUp(e.target.value === "follow_up")}>
            <option value="appointment">Appointment</option>
            <option value="follow_up">Follow-up</option>
          </select>
        </div>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {conflictSlot && slots && (
          <div className={styles.field} aria-live="polite">
            <span className={styles.label}>{alternatives.length > 0 ? "Nearest free times" : "No other free times for this doctor on this day. Try another date or doctor."}</span>
            <div className={styles.altRow}>
              {alternatives.map((s) => (
                <button type="button" key={s} className={`${styles.btn} ${styles.tap}`} onClick={() => { setSlot(s); setConflictSlot(null); setError(null); }}>{clinicTime(s, timezone)}</button>
              ))}
            </div>
          </div>
        )}

        <div className={styles.actions}>
          <button type="button" className={`${styles.btn} ${styles.tap}`} onClick={onClose}>Cancel</button>
          <button type="submit" className={`${styles.btnPrimary} ${styles.tap}`} disabled={registering || saving || !patient || !slot}>
            {saving ? "Booking…" : "Book appointment"}
          </button>
        </div>
      </form>
    </div>
  );
}
