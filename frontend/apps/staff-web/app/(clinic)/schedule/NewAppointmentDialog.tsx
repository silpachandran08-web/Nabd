"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { can } from "../../lib/permissions";
import styles from "./schedule.module.css";
import { hijriLabel } from "../../lib/hijri";
import type { DuplicateCandidate } from "@nabd/api-client/types";
import { age } from "@nabd/domain";
import { clinicToday } from "../../lib/clinicTime";
import { api, ApiError, intentKeys } from "../../lib/api";
import { getter, key } from "../../lib/query";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import { usePatientSearch, type PatientHit } from "../../lib/usePatientSearch";
import Dialog from "../../components/ui/Dialog";

type Patient = PatientHit;
type Candidate = DuplicateCandidate;
type BookingProblem = { type?: string; detail?: string };

/** Plain-language booking refusals keyed by the problem type slug; anything unknown gets the generic line, never the raw server text. */
function bookingMessage(status: number, p: BookingProblem | null | undefined): string {
  const slug = p?.type?.split("/").pop();
  if (slug === "slot-taken") return "That time was just taken by another booking.";
  if (slug === "doctor-on-leave") return "The doctor is on leave that day. Choose another day.";
  if (slug === "outside-hours") return "The doctor doesn't work at that time. Choose a listed time.";
  if (slug === "off-grid") return "That isn't a bookable slot start. Choose a listed time.";
  if (slug === "session-full") return "This doctor's session is full.";
  if (slug === "in-past") return "That time has already passed. Choose a later time.";
  if (slug === "clinic-closed") return p?.detail || "The clinic is closed on that day.";
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
export default function NewAppointmentDialog({ doctors, timezone, initialDoctorId, initialDate, initialSlot, onClose, onBooked }: {
  doctors: { id: string; name: string }[];
  timezone: string;
  initialDoctorId: string;
  initialDate: string;
  initialSlot: string | null;
  onClose: () => void;
  onBooked: () => void;
}) {
  const [query, setQuery] = useState("");
  const { results, failed: searchFailed, empty: noMatch } = usePatientSearch(query, 8);
  const [patient, setPatient] = useState<Patient | null>(null);
  const [doctorId, setDoctorId] = useState(initialDoctorId);
  const [date, setDate] = useState(initialDate);
  const [pickedSlot, setSlot] = useState<string>(initialSlot ?? "");
  const [followUp, setFollowUp] = useState(false);
  const fe = useFormErrors("appt", { patientId: "Patient", doctorId: "Doctor", startTime: "Time", name: "Name", phone: "Phone", dob: "Date of birth", gender: "Sex" });
  const [saving, setSaving] = useState(false);
  const [conflictSlot, setConflictSlot] = useState<string | null>(null); // the slot a refusal was about; drives "nearest free times"
  const keys = useRef(intentKeys());
  // Quick-create patient (patients:create). shortcut: no guardian/consent flow, under-18s go to Arrivals; no "different person" override, upgrade with REC patient-register work.
  const canRegister = can("patients:create");
  const [registering, setRegistering] = useState(false);
  const [np, setNp] = useState({ name: "", phone: "", dob: "", gender: "male" });
  const [dupes, setDupes] = useState<Candidate[]>([]);

  // Free slots for the chosen doctor and day; a pre-selected slot is kept only while it is still free.
  const slotsQ = useQuery({
    queryKey: key("availability", { doctorId, date }),
    queryFn: getter<string[]>(`/doctors/${doctorId}/availability?date=${date}`),
    enabled: !!doctorId && !!date, staleTime: 0, gcTime: 0,
  });
  const slotsFailed = slotsQ.isError;
  const slots = !slotsQ.data ? (slotsFailed ? [] : null) : slotsQ.data;
  const slot = slots && !slots.includes(pickedSlot) ? "" : pickedSlot;
  const { refetch: reloadSlots } = slotsQ;


  async function book(e: React.FormEvent) {
    e.preventDefault();
    if (!patient || !doctorId || !slot || saving) return;
    setSaving(true);
    fe.clear();
    const body = { patientId: patient.id, doctorId, startTime: slot, isFollowUp: followUp };
    try {
      await api.post("/appointments", body, { idempotencyKey: keys.current.get("book", body) });
      keys.current.done("book");
      onBooked();
    } catch (err) {
      if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) {
        fe.set(bookingMessage(err.status, err.problem as BookingProblem));
        const slug = (err.problem as BookingProblem).type?.split("/").pop();
        if (slug === "slot-taken" || slug === "session-full") { setConflictSlot(slot); void reloadSlots(); }
      } else fe.fail(err, "Couldn't book the appointment. Check the details and try again.");
    } finally {
      setSaving(false);
    }
  }

  async function registerPatient(e: React.FormEvent) {
    e.preventDefault();
    if (age(np.dob, clinicToday(timezone)) < 18) { fe.set("Patients under 18 need a guardian. Register them from Arrivals, then book here."); return; }
    if (saving) return;
    setSaving(true);
    fe.clear();
    try {
      setPatient(await api.post<Patient>("/patients", np, { idempotencyKey: keys.current.get("register", np) }));
      keys.current.done("register");
      setRegistering(false);
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setDupes([...((err.problem.candidates as Candidate[] | undefined) ?? [])].sort((x, y) => y.matchScore - x.matchScore));
        fe.set("A patient with these details may already exist. Choose them below instead of registering again.");
      } else if (err instanceof ApiError && err.status === 403) fe.set("You don't have permission to register patients.");
      else if (err instanceof ApiError && !(err.status === 400 && err.errors.length)) fe.set("Couldn't register the patient. Check the details and try again.");
      else fe.fail(err, "Couldn't register the patient. Check the details and try again.");
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
    <Dialog title="New appointment" onClose={onClose}>
      <form onSubmit={registering ? registerPatient : book}>
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
              {!searchFailed && noMatch && !registering && (
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
                  <input aria-label="Patient name" className={`${styles.input} ${styles.tap}`} required placeholder="Full name" value={np.name} {...fe.attrs("name")} onChange={(e) => { fe.clearField("name"); setNp({ ...np, name: e.target.value }); }} />
                  <FieldError fe={fe} name="name" className={styles.resultSub} />
                  <input aria-label="Patient phone" className={`${styles.input} ${styles.tap}`} required type="tel" placeholder="Phone" value={np.phone} {...fe.attrs("phone")} onChange={(e) => { fe.clearField("phone"); setNp({ ...np, phone: e.target.value }); }} />
                  <FieldError fe={fe} name="phone" className={styles.resultSub} />
                  <input aria-label="Date of birth" className={`${styles.input} ${styles.tap}`} required type="date" value={np.dob} {...fe.attrs("dob")} onChange={(e) => { fe.clearField("dob"); setNp({ ...np, dob: e.target.value }); }} />
                  <FieldError fe={fe} name="dob" className={styles.resultSub} />
                  <select aria-label="Sex" className={`${styles.input} ${styles.tap}`} value={np.gender} {...fe.attrs("gender")} onChange={(e) => { fe.clearField("gender"); setNp({ ...np, gender: e.target.value }); }}>
                    <option value="male">Male</option><option value="female">Female</option><option value="other">Other</option>
                  </select>
                  <FieldError fe={fe} name="gender" className={styles.resultSub} />
                  {dupes.map((c) => (
                    <button type="button" key={c.patientId} className={`${styles.result} ${styles.tap}`} onClick={() => { setPatient({ id: c.patientId, name: c.name, phone: c.phone, mrn: "Existing record" }); setRegistering(false); setDupes([]); fe.clear(); }}>
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
          <select id="appt-doctor" className={`${styles.input} ${styles.tap}`} value={doctorId} {...fe.attrs("doctorId")} onChange={(e) => { fe.clearField("doctorId"); setDoctorId(e.target.value); }}>
            {doctors.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
          <FieldError fe={fe} name="doctorId" className={styles.resultSub} />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-date">Date</label>
          <input id="appt-date" type="date" className={`${styles.input} ${styles.tap}`} value={date} onChange={(e) => setDate(e.target.value)} />
          {date && <span className={styles.hijri} data-testid="hijri-date">{hijriLabel(date)}</span>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-time">Time</label>
          <select id="appt-time" className={`${styles.input} ${styles.tap}`} value={slot} {...fe.attrs("startTime")} onChange={(e) => { fe.clearField("startTime"); setSlot(e.target.value); setConflictSlot(null); }} disabled={!slots || slots.length === 0}>
            <option value="">{slots === null ? "Loading free slots…" : slotsFailed ? "Couldn't load free slots" : slots.length === 0 ? "No free slots on this day" : "Choose a time"}</option>
            {(slots ?? []).map((s) => <option key={s} value={s}>{clinicTime(s, timezone)}</option>)}
          </select>
          <FieldError fe={fe} name="startTime" className={styles.resultSub} />
          {slotsFailed && <button type="button" className={`${styles.btn} ${styles.tap}`} onClick={() => void reloadSlots()}>Retry</button>}
          {slots && slots.length === 0 && !slotsFailed && <div className={styles.resultSub}>Try another date or doctor.</div>}
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="appt-visit">Visit type</label>
          <select id="appt-visit" className={`${styles.input} ${styles.tap}`} value={followUp ? "follow_up" : "appointment"} onChange={(e) => setFollowUp(e.target.value === "follow_up")}>
            <option value="appointment">Appointment</option>
            <option value="follow_up">Follow-up</option>
          </select>
        </div>

        <FieldError fe={fe} name="patientId" className={styles.resultSub} />
        <ErrorSummary fe={fe} className={styles.error} />
        {conflictSlot && slots && (
          <div className={styles.field} aria-live="polite">
            <span className={styles.label}>{alternatives.length > 0 ? "Nearest free times" : "No other free times for this doctor on this day. Try another date or doctor."}</span>
            <div className={styles.altRow}>
              {alternatives.map((s) => (
                <button type="button" key={s} className={`${styles.btn} ${styles.tap}`} onClick={() => { setSlot(s); setConflictSlot(null); fe.clear(); }}>{clinicTime(s, timezone)}</button>
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
    </Dialog>
  );
}
