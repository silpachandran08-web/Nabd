"use client";

import { useEffect, useRef, useState } from "react";
import styles from "./patients.module.css";
import StatusPill from "../components/ui/StatusPill";
import { api, ApiError, intentKeys } from "../lib/api";
import { ErrorSummary, FieldError, useFormErrors } from "../lib/formErrors";
import type { PatientPage } from "@nabd/api-client/generated";
import type { DuplicateCandidate } from "@nabd/api-client/types";
import { age } from "@nabd/domain";
import { clinicToday } from "../lib/clinicTime";

type Candidate = DuplicateCandidate;

// PatientMatchCandidateResponse.matchScore is a 0-1 fraction (1.0 = exact phone match, else name similarity).
function formatScore(score: number): string {
  if (!Number.isFinite(score)) return "—";
  return `${Math.round(Math.min(1, Math.max(0, score)) * 100)}%`;
}

// TODO(needs-legal-review): consent wording is a placeholder from S4 CGV-005; legal must approve before go-live.
const GUARDIAN_CONSENT_TEXT = "Guardian has agreed to access on this patient's behalf";
const CONSENT_CHANNELS = [["in_person", "In person"], ["phone", "Phone"], ["written", "Written"]] as const;

function useEscape(onClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
}

/** ACC-028: the server's field error for the phone names the clinic country; it reads "Mobile <message>." under the input. */
export const phoneFieldText = (message: string) => `Mobile ${message}.`;

const REGISTER_LABELS = { name: "Full name", phone: "Mobile", gender: "Sex", address: "Address", nationalId: "National ID", guardianId: "Guardian" };

/**
 * Register patient (POST /v1/patients). A minor needs a guardian (NB-080). If the server finds
 * likely existing records (409), they're shown first; "Different person" re-sends with
 * confirmedNotDuplicate, and the record then shows under Duplicate review unless it's family.
 */
export function RegisterDialog({ onClose, onRegistered, onOpenExisting }: {
  onClose: () => void; onRegistered: (id: string) => void; onOpenExisting: (id: string) => void;
}) {
  const [form, setForm] = useState({ name: "", phone: "", dob: "", gender: "", address: "", nationalId: "" });
  const [guardianQuery, setGuardianQuery] = useState("");
  const [guardianResults, setGuardianResults] = useState<{ id: string; name: string; phone: string }[]>([]);
  const [guardian, setGuardian] = useState<{ id: string; name: string } | null>(null);
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [consent, setConsent] = useState(false);
  const [channel, setChannel] = useState("");
  const fe = useFormErrors("reg", REGISTER_LABELS);
  const [saving, setSaving] = useState(false);
  const keys = useRef(intentKeys()); // one Idempotency-Key per registration intent, reused when the same request is re-sent
  useEscape(onClose);

  const minor = !!form.dob && age(form.dob, clinicToday()) < 18;

  useEffect(() => {
    const q = guardianQuery.trim();
    let cancelled = false;
    const timer = setTimeout(async () => {
      if (q.length < 2) {
        if (!cancelled) setGuardianResults([]);
        return;
      }
      try {
        const page = await api.get<PatientPage>(`/patients?q=${encodeURIComponent(q)}&limit=6`);
        if (!cancelled) setGuardianResults((page.data ?? []) as { id: string; name: string; phone: string }[]);
      } catch { /* the list stays empty; typing again retries */ }
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [guardianQuery]);

  async function send(confirmed: boolean) {
    if (saving) return;
    setSaving(true);
    fe.clear();
    const body = {
      name: form.name.trim(), phone: form.phone.trim(), dob: form.dob, gender: form.gender,
      address: form.address.trim() || null, ...(form.nationalId.trim() ? { nationalId: form.nationalId.trim() } : {}), guardianId: minor ? guardian?.id ?? null : null,
      ...(minor && guardian ? { guardianConsent: { confirmed: true, channel } } : {}),
      ...(confirmed ? { confirmedNotDuplicate: true } : {}),
    };
    try {
      const created = await api.post<{ id: string }>("/patients", body, { idempotencyKey: keys.current.get("register", body) });
      keys.current.done("register");
      onRegistered(created.id);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && Array.isArray(e.problem.candidates)) {
        setCandidates(e.problem.candidates as Candidate[]); // a definitive answer: the next send is a new intent
        keys.current.done("register");
      } else {
        if (e instanceof ApiError && e.status === 400 && e.errors.length) setCandidates(null);
        if (e instanceof ApiError && e.status >= 500) fe.set(e.detail || e.title || "Couldn't register the patient."); // front desk reads the server's reason here
        else fe.fail(e, "Couldn't register the patient.");
      }
    } finally {
      setSaving(false);
    }
  }

  const ready = form.name.trim() && form.phone.trim() && form.dob && form.gender && (!minor || (guardian && consent && channel));
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
                <StatusPill tone="info" icon="users" label={`Match ${formatScore(c.matchScore)}`} />
                <button type="button" className={styles.secondaryBtn} onClick={() => onOpenExisting(c.patientId)}>Open</button>
              </div>
            ))}
            <ErrorSummary fe={fe} className={styles.errorState} />
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
            <input id="reg-name" className={styles.input} value={form.name} autoFocus {...fe.attrs("name")} onChange={(e) => { fe.clearField("name"); setForm({ ...form, name: e.target.value }); }} />
            <FieldError fe={fe} name="name" className={styles.errorInline} />
            <label className={styles.fieldLabel} htmlFor="reg-phone">Mobile</label>
            <input id="reg-phone" className={styles.input} value={form.phone} inputMode="tel" placeholder="+91 98765 43210"
              {...fe.attrs("phone")}
              onChange={(e) => { setForm({ ...form, phone: e.target.value }); fe.clearField("phone"); }} />
            <FieldError fe={fe} name="phone" className={styles.errorInline} text={phoneFieldText} />
            <div className={styles.fieldRow}>
              <div>
                <label className={styles.fieldLabel} htmlFor="reg-dob">Date of birth</label>
                <input id="reg-dob" type="date" className={styles.input} value={form.dob} max={new Date().toLocaleDateString("en-CA")} {...fe.attrs("dob")}
                  onChange={(e) => { fe.clearField("dob"); setForm({ ...form, dob: e.target.value }); }} />
                <FieldError fe={fe} name="dob" className={styles.errorInline} />
              </div>
              <div>
                <label className={styles.fieldLabel} htmlFor="reg-gender">Sex</label>
                <select id="reg-gender" className={styles.input} value={form.gender} {...fe.attrs("gender")} onChange={(e) => { fe.clearField("gender"); setForm({ ...form, gender: e.target.value }); }}>
                  <option value="">Choose</option>
                  <option value="female">Female</option>
                  <option value="male">Male</option>
                  <option value="other">Other</option>
                </select>
                <FieldError fe={fe} name="gender" className={styles.errorInline} />
              </div>
            </div>
            <label className={styles.fieldLabel} htmlFor="reg-address">Address (optional)</label>
            <input id="reg-address" className={styles.input} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            {/* shortcut: no format check (Aadhaar/Iqama rules are not in the contract), when the clinic region rules are agreed. Write-only: the API never returns it. */}
            <label className={styles.fieldLabel} htmlFor="reg-national-id">National ID, Aadhaar or Iqama (optional)</label>
            <input id="reg-national-id" className={styles.input} value={form.nationalId} autoComplete="off" {...fe.attrs("nationalId")} onChange={(e) => { fe.clearField("nationalId"); setForm({ ...form, nationalId: e.target.value }); }} />
            <FieldError fe={fe} name="nationalId" className={styles.errorInline} />
            {minor && (
              <>
                <label className={styles.fieldLabel} htmlFor="reg-guardian">Guardian (required for under-18s)</label>
                {guardian ? (
                  <>
                    <button type="button" className={styles.secondaryBtn} onClick={() => { setGuardian(null); setConsent(false); setChannel(""); setGuardianQuery(""); }}>{guardian.name} · change</button>
                    <label className={styles.fieldLabel}>
                      <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} /> {GUARDIAN_CONSENT_TEXT}
                    </label>
                    <label className={styles.fieldLabel} htmlFor="reg-consent-channel">How was consent given?</label>
                    <select id="reg-consent-channel" className={styles.input} value={channel} onChange={(e) => setChannel(e.target.value)}>
                      <option value="">Select…</option>
                      {CONSENT_CHANNELS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                    </select>
                  </>
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
            <ErrorSummary fe={fe} className={styles.errorState} />
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
