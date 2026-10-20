"use client";

import { useEffect, useState } from "react";
import styles from "./patients.module.css";
import StatusPill from "../components/ui/StatusPill";
import { fingerprint, submitKeyed } from "../lib/idempotency";

type AuthedFetch = (path: string, init?: RequestInit) => Promise<Response | null>;
export type Problem = { title: string; detail: string; errors?: { field: string; message: string }[] };
type Candidate = { patientId: string; name: string; phone: string; matchScore: number };

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

/** ACC-028: the server's field error for the phone (it names the clinic country), shown under the input. */
export function phoneFieldError(p: Problem | null): string | null {
  const e = p?.errors?.find((x) => x.field === "phone");
  return e ? `Mobile ${e.message}.` : null;
}

/**
 * Register patient (POST /v1/patients). A minor needs a guardian (NB-080). If the server finds
 * likely existing records (409), they're shown first; "Different person" re-sends with
 * confirmedNotDuplicate, and the record then shows under Duplicate review unless it's family.
 */
export function RegisterDialog({ authedFetch, onClose, onRegistered, onOpenExisting }: {
  authedFetch: AuthedFetch; onClose: () => void; onRegistered: (id: string) => void; onOpenExisting: (id: string) => void;
}) {
  const [form, setForm] = useState({ name: "", phone: "", dob: "", gender: "", address: "", nationalId: "" });
  const [guardianQuery, setGuardianQuery] = useState("");
  const [guardianResults, setGuardianResults] = useState<{ id: string; name: string; phone: string }[]>([]);
  const [guardian, setGuardian] = useState<{ id: string; name: string } | null>(null);
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [consent, setConsent] = useState(false);
  const [channel, setChannel] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [phoneError, setPhoneError] = useState<string | null>(null);
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
        address: form.address.trim() || null, ...(form.nationalId.trim() ? { nationalId: form.nationalId.trim() } : {}), guardianId: minor ? guardian?.id ?? null : null,
        ...(minor && guardian ? { guardianConsent: { confirmed: true, channel } } : {}),
        ...(confirmed ? { confirmedNotDuplicate: true } : {}),
      };
      const res = await submitKeyed(authedFetch, "/patients", body, `reg.${fingerprint(JSON.stringify(body))}`);
      if (!res) return;
      if (res.status === 409) {
        const dup: { candidates: Candidate[] } = await res.json();
        setCandidates(dup.candidates);
        return;
      }
      if (!res.ok) {
        const p: Problem | null = await res.json().catch(() => null);
        const fieldErr = res.status === 400 ? phoneFieldError(p) : null;
        if (fieldErr) {
          setCandidates(null);
          setPhoneError(fieldErr);
        } else setError(p?.detail || p?.title || "Couldn't register the patient.");
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
              aria-invalid={phoneError ? true : undefined} aria-describedby={phoneError ? "reg-phone-error" : undefined}
              onChange={(e) => { setForm({ ...form, phone: e.target.value }); setPhoneError(null); }} />
            {phoneError && <div id="reg-phone-error" className={styles.errorInline} role="alert">{phoneError}</div>}
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
            {/* shortcut: no format check (Aadhaar/Iqama rules are not in the contract), when the clinic region rules are agreed. Write-only: the API never returns it. */}
            <label className={styles.fieldLabel} htmlFor="reg-national-id">National ID, Aadhaar or Iqama (optional)</label>
            <input id="reg-national-id" className={styles.input} value={form.nationalId} autoComplete="off" onChange={(e) => setForm({ ...form, nationalId: e.target.value })} />
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
