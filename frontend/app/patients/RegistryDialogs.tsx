"use client";

import { useEffect, useState } from "react";
import styles from "./patients.module.css";

type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;
type Problem = { title: string; detail: string };
type Candidate = { patientId: string; name: string; phone: string; matchScore: number };

function useEscape(onClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
}

async function detail(res: Response, fallback: string): Promise<string> {
  const p: Problem | null = await res.json().catch(() => null);
  return p?.detail || p?.title || fallback;
}

/**
 * Check in from the registry. With a booking today it checks in against it (POST /queue/check-in
 * with appointmentId); otherwise it's a walk-in for the chosen doctor.
 */
export function CheckInDialog({ patient, authedFetch, onClose, onDone }: {
  patient: { id: string; name: string; nextAppointmentToday: boolean; nextAppointmentId: string | null;
    nextDoctorId: string | null; nextDoctorName: string | null; nextAppointment: string | null };
  authedFetch: AuthedFetch; onClose: () => void; onDone: (token: number) => void;
}) {
  const booked = patient.nextAppointmentToday && patient.nextAppointmentId && patient.nextDoctorId;
  const [doctors, setDoctors] = useState<{ id: string; name: string }[] | null>(null);
  const [doctorId, setDoctorId] = useState(patient.nextDoctorId ?? "");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEscape(onClose);

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
        setError(await detail(res, "Couldn't check the patient in."));
        return;
      }
      const entry: { tokenNumber: number } = await res.json();
      onDone(entry.tokenNumber);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  const time = patient.nextAppointment
    ? new Date(patient.nextAppointment).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" }) : "";
  return (
    <div className={styles.dialogOverlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="checkin-title"
        onClick={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2 id="checkin-title" className={styles.dialogTitle}>Check in {patient.name}</h2>
        {booked ? (
          <p className={styles.dialogSub}>Today&apos;s {time} appointment with {patient.nextDoctorName}.</p>
        ) : (
          <>
            <p className={styles.dialogSub}>No booking today — this checks them in as a walk-in.</p>
            <label className={styles.fieldLabel} htmlFor="checkin-doctor">Doctor</label>
            <select id="checkin-doctor" className={styles.input} value={doctorId} onChange={(e) => setDoctorId(e.target.value)} autoFocus>
              <option value="">{doctors === null ? "Loading…" : "Choose a doctor"}</option>
              {(doctors ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </>
        )}
        {error && <div className={styles.errorState} role="alert">{error}</div>}
        <div className={styles.dialogActions}>
          <button type="button" className={styles.secondaryBtn} onClick={onClose}>Cancel</button>
          <button type="submit" className={styles.primaryBtn} disabled={saving || (!booked && !doctorId)}>
            {saving ? "Checking in…" : "Check in"}
          </button>
        </div>
      </form>
    </div>
  );
}

/**
 * Register patient (POST /v1/patients). A minor needs a guardian (NB-080). If the server finds
 * likely existing records (409), they're shown first; "Different person" re-sends with
 * confirmedNotDuplicate, and the record then shows under Duplicate review unless it's family.
 */
export function RegisterDialog({ authedFetch, onClose, onRegistered, onOpenExisting }: {
  authedFetch: AuthedFetch; onClose: () => void; onRegistered: (id: string) => void; onOpenExisting: (id: string) => void;
}) {
  const [form, setForm] = useState({ name: "", phone: "", dob: "", gender: "", address: "" });
  const [guardianQuery, setGuardianQuery] = useState("");
  const [guardianResults, setGuardianResults] = useState<{ id: string; name: string; phone: string }[]>([]);
  const [guardian, setGuardian] = useState<{ id: string; name: string } | null>(null);
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEscape(onClose);

  const minor = (() => {
    if (!form.dob) return false;
    const [y, m, d] = form.dob.split("-").map(Number);
    const now = new Date();
    let age = now.getFullYear() - y;
    if (now.getMonth() + 1 < m || (now.getMonth() + 1 === m && now.getDate() < d)) age--;
    return age < 18;
  })();

  useEffect(() => {
    const q = guardianQuery.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) {
        if (!cancelled) setGuardianResults([]);
        return;
      }
      const res = await authedFetch(`/patients?q=${encodeURIComponent(q)}&limit=6`);
      if (cancelled || !res?.ok) return;
      const page: { data: { id: string; name: string; phone: string }[] } = await res.json();
      if (!cancelled) setGuardianResults(page.data);
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [guardianQuery, authedFetch]);

  async function send(confirmed: boolean) {
    setSaving(true);
    setError(null);
    try {
      const body = {
        name: form.name.trim(), phone: form.phone.trim(), dob: form.dob, gender: form.gender,
        address: form.address.trim() || null, guardianId: minor ? guardian?.id ?? null : null,
        ...(confirmed ? { confirmedNotDuplicate: true } : {}),
      };
      const res = await authedFetch("/patients", { method: "POST", body: JSON.stringify(body) });
      if (!res) return;
      if (res.status === 409) {
        const dup: { candidates: Candidate[] } = await res.json();
        setCandidates(dup.candidates);
        return;
      }
      if (!res.ok) {
        setError(await detail(res, "Couldn't register the patient."));
        return;
      }
      const created: { id: string } = await res.json();
      onRegistered(created.id);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setSaving(false);
    }
  }

  const ready = form.name.trim() && form.phone.trim() && form.dob && form.gender && (!minor || guardian);
  return (
    <div className={styles.dialogOverlay} onClick={onClose}>
      <form className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="register-title"
        onClick={(e) => e.stopPropagation()} onSubmit={(e) => { e.preventDefault(); void send(false); }}>
        <h2 id="register-title" className={styles.dialogTitle}>Register patient</h2>

        {candidates ? (
          <>
            <p className={styles.dialogSub}>These existing records look like the same person. Open one, or register a new record if this is someone else.</p>
            {candidates.map((c) => (
              <div key={c.patientId} className={styles.candidateRow}>
                <span><span className={styles.patientName}>{c.name}</span><span className={styles.mrn}>{c.phone}</span></span>
                <button type="button" className={styles.secondaryBtn} onClick={() => onOpenExisting(c.patientId)}>Open</button>
              </div>
            ))}
            {error && <div className={styles.errorState} role="alert">{error}</div>}
            <div className={styles.dialogActions}>
              <button type="button" className={styles.secondaryBtn} onClick={() => setCandidates(null)}>Back</button>
              <button type="button" className={styles.primaryBtn} disabled={saving} onClick={() => void send(true)}>
                {saving ? "Registering…" : "Different person — register"}
              </button>
            </div>
          </>
        ) : (
          <>
            <label className={styles.fieldLabel} htmlFor="reg-name">Full name</label>
            <input id="reg-name" className={styles.input} value={form.name} autoFocus onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <label className={styles.fieldLabel} htmlFor="reg-phone">Mobile</label>
            <input id="reg-phone" className={styles.input} value={form.phone} inputMode="tel" placeholder="+91 98765 43210"
              onChange={(e) => setForm({ ...form, phone: e.target.value })} />
            <div className={styles.fieldRow}>
              <div>
                <label className={styles.fieldLabel} htmlFor="reg-dob">Date of birth</label>
                <input id="reg-dob" type="date" className={styles.input} value={form.dob} max={new Date().toLocaleDateString("en-CA")}
                  onChange={(e) => setForm({ ...form, dob: e.target.value })} />
              </div>
              <div>
                <label className={styles.fieldLabel} htmlFor="reg-gender">Sex</label>
                <select id="reg-gender" className={styles.input} value={form.gender} onChange={(e) => setForm({ ...form, gender: e.target.value })}>
                  <option value="">Choose</option>
                  <option value="female">Female</option>
                  <option value="male">Male</option>
                  <option value="other">Other</option>
                </select>
              </div>
            </div>
            <label className={styles.fieldLabel} htmlFor="reg-address">Address (optional)</label>
            <input id="reg-address" className={styles.input} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            {minor && (
              <>
                <label className={styles.fieldLabel} htmlFor="reg-guardian">Guardian (required for under-18s)</label>
                {guardian ? (
                  <button type="button" className={styles.secondaryBtn} onClick={() => setGuardian(null)}>{guardian.name} · change</button>
                ) : (
                  <>
                    <input id="reg-guardian" className={styles.input} value={guardianQuery} placeholder="Search the guardian's record by name or mobile"
                      onChange={(e) => setGuardianQuery(e.target.value)} />
                    {guardianResults.map((g) => (
                      <button type="button" key={g.id} className={styles.candidateRow} onClick={() => { setGuardian({ id: g.id, name: g.name }); setGuardianResults([]); }}>
                        <span><span className={styles.patientName}>{g.name}</span><span className={styles.mrn}>{g.phone}</span></span>
                      </button>
                    ))}
                  </>
                )}
              </>
            )}
            {error && <div className={styles.errorState} role="alert">{error}</div>}
            <div className={styles.dialogActions}>
              <button type="button" className={styles.secondaryBtn} onClick={onClose}>Cancel</button>
              <button type="submit" className={styles.primaryBtn} disabled={saving || !ready}>{saving ? "Registering…" : "Register patient"}</button>
            </div>
          </>
        )}
      </form>
    </div>
  );
}
