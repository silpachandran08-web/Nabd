"use client";

import { useEffect, useRef, useState } from "react";
import { useSlices } from "./useSlices";
import styles from "./patients.module.css";
import StatusPill from "../../components/ui/StatusPill";
import Dialog from "../../components/ui/Dialog";
import { api, ApiError, intentKeys } from "../../lib/api";
import { ErrorSummary, FieldError, useFormErrors } from "../../lib/formErrors";
import type { PatientPage } from "@nabd/api-client/generated";
import type { DuplicateCandidate } from "@nabd/api-client/types";
import { age, parseAbhaQr } from "@nabd/domain";
import AbhaPanel from "./AbhaPanel";
import { clinicToday } from "../../lib/clinicTime";
import { can } from "../../lib/permissions";
import ConsentBlock from "./ConsentBlock";
import AssistedConsent from "./AssistedConsent";
import { buildCaptureBody, consentProblemText, NO_CONSENT, type ConsentState, type Toggles } from "../../lib/consentCapture";

type Candidate = DuplicateCandidate;

// PatientMatchCandidateResponse.matchScore is a 0-1 fraction (1.0 = exact phone match, else name similarity).
function formatScore(score: number): string {
  if (!Number.isFinite(score)) return "—";
  return `${Math.round(Math.min(1, Math.max(0, score)) * 100)}%`;
}

// TODO(needs-legal-review): consent wording is a placeholder from S4 CGV-005; legal must approve before go-live.
const GUARDIAN_CONSENT_TEXT = "Guardian has agreed to access on this patient's behalf";
const CONSENT_CHANNELS = [["in_person", "In person"], ["phone", "Phone"], ["written", "Written"]] as const;

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
  // FEF-031: related fields share one useState each (a component keeps at most 8 hooks): guardian search, consent capture, registration result.
  const gs = useSlices({ guardianQuery: "", guardianResults: [] as { id: string; name: string; phone: string }[], guardian: null as { id: string; name: string } | null, candidates: null as Candidate[] | null });
  const [guardianQuery, setGuardianQuery] = gs("guardianQuery");
  const [guardianResults, setGuardianResults] = gs("guardianResults");
  const [guardian, setGuardian] = gs("guardian");
  const [candidates, setCandidates] = gs("candidates");
  const cs = useSlices({ consent: false, channel: "", toggles: NO_CONSENT as Toggles, emergency: false, assisted: null as { language: string; witness: string } | null, assistedOpen: false });
  const [consent, setConsent] = cs("consent");
  const [channel, setChannel] = cs("channel");
  // IDC-004/007: itemised consent captured with the registration (only for staff who may record it, patients:edit)
  const canConsent = can("patients:edit");
  const [toggles, setToggles] = cs("toggles");
  const [emergency, setEmergency] = cs("emergency");
  const [assisted, setAssisted] = cs("assisted");
  const [assistedOpen, setAssistedOpen] = cs("assistedOpen");
  const rs = useSlices({ registeredId: null as string | null, abhaOn: false, createdId: null as string | null, qr: "" });
  const [registeredId, setRegisteredId] = rs("registeredId"); // registered, consent not saved yet: retry only the consent
  const fe = useFormErrors("reg", REGISTER_LABELS);
  const [saving, setSaving] = useState(false);
  const [abhaOn, setAbhaOn] = rs("abhaOn"); // IDC-017: GET /abha/status; the panel is absent unless ABDM is on and the user has patients:edit
  const [createdId, setCreatedId] = rs("createdId");
  const [qr, setQr] = rs("qr");
  const keys = useRef(intentKeys()); // one Idempotency-Key per registration intent, reused when the same request is re-sent

  const minor = !!form.dob && age(form.dob, clinicToday()) < 18;

  useEffect(() => {
    if (!can("patients:edit")) return;
    api.get<{ enabled: boolean }>("/abha/status").then((r) => setAbhaOn(r.enabled)).catch(() => setAbhaOn(false));
  }, []);

  function applyQr(text: string) { // IDC-017: pasted or scanned ABHA QR payload fills the demographic fields it carries
    setQr(text);
    const p = parseAbhaQr(text);
    if (p) setForm((f) => ({ ...f, name: p.name ?? f.name, dob: p.dob ?? f.dob, gender: p.gender ?? f.gender, phone: p.phone ?? f.phone }));
  }

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

  const finish = (id: string) => { if (abhaOn) setCreatedId(id); else onRegistered(id); }; // IDC-017: the ABHA step comes after consent
  async function saveConsent(id: string): Promise<boolean> {
    const state = await api.get<ConsentState>(`/patients/${id}/consents`);
    const lang = assisted?.language ?? "en";
    const body = buildCaptureBody(toggles, state.notices, lang, {
      channel: assisted ? "ASSISTED" : "DESK", guardianPatientId: minor ? guardian?.id : null,
      witnessStaffIds: assisted?.witness ? [assisted.witness] : [], emergency, minor,
    });
    if (!body) { fe.set("The clinic has no privacy notice in this language yet. Publish one in Settings, then record consent from the patient file."); return false; }
    await api.post(`/patients/${id}/consents`, body, { idempotencyKey: keys.current.get("consent", body) });
    keys.current.done("consent");
    return true;
  }

  async function send(confirmed: boolean) {
    if (saving) return;
    setSaving(true);
    fe.clear();
    if (registeredId) { // the record exists; only the consent is left to save
      try { if (await saveConsent(registeredId)) finish(registeredId); }
      catch (e) { fe.set(e instanceof ApiError ? consentProblemText(String(e.problem?.type ?? "").split("/").pop() ?? "", e.detail || "Couldn't save the consent.") : "Couldn't save the consent."); }
      finally { setSaving(false); }
      return;
    }
    const body = {
      name: form.name.trim(), phone: form.phone.trim(), dob: form.dob, gender: form.gender,
      address: form.address.trim() || null, ...(form.nationalId.trim() ? { nationalId: form.nationalId.trim() } : {}), guardianId: minor ? guardian?.id ?? null : null,
      ...(minor && guardian ? { guardianConsent: { confirmed: true, channel } } : {}),
      ...(confirmed ? { confirmedNotDuplicate: true } : {}),
    };
    try {
      const created = await api.post<{ id: string }>("/patients", body, { idempotencyKey: keys.current.get("register", body) });
      keys.current.done("register");
      if (canConsent) {
        setRegisteredId(created.id);
        try { if (!(await saveConsent(created.id))) return; }
        catch (e) { fe.set((e instanceof ApiError ? consentProblemText(String(e.problem?.type ?? "").split("/").pop() ?? "", e.detail || "") : "") || "The patient is registered, but the consent was not saved. Try again."); return; }
      }
      finish(created.id);
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
    <Dialog title="Register patient" onClose={onClose}>
      <form className={styles.dialogForm} onSubmit={(e) => { e.preventDefault(); void send(false); }}>

        {createdId ? (
          <AbhaPanel patientId={createdId} onDone={() => onRegistered(createdId)} onOpenExisting={onOpenExisting} />
        ) : candidates ? (
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
            {abhaOn && (
              <>
                <label className={styles.fieldLabel} htmlFor="reg-abha-qr">ABHA QR (optional): paste the scanned text to fill the form</label>
                <input id="reg-abha-qr" className={styles.input} value={qr} autoComplete="off" onChange={(e) => applyQr(e.target.value)} />
              </>
            )}
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
            {canConsent && !registeredId && (
              <>
                <ConsentBlock value={toggles} onChange={setToggles} minor={minor} />
                {!toggles.processing && (
                  <label className={styles.consentRow}>
                    <input type="checkbox" checked={emergency} onChange={(e) => setEmergency(e.target.checked)} />
                    <span>Emergency or treatment only: record without processing consent</span>
                  </label>
                )}
                <button type="button" className={styles.secondaryBtn} onClick={() => setAssistedOpen(true)}>{assisted ? "Assisted consent ✓" : "Assisted consent"}</button>
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
      {assistedOpen && (
        <AssistedConsent value={toggles} onChange={setToggles} minor={minor} language={assisted?.language ?? "en"} witness={assisted?.witness ?? ""}
          onLanguage={(language) => setAssisted({ language, witness: assisted?.witness ?? "" })}
          onWitness={(witness) => setAssisted({ language: assisted?.language ?? "en", witness })}
          onDone={() => { setAssisted((a) => a ?? { language: "en", witness: "" }); setAssistedOpen(false); }} onClose={() => setAssistedOpen(false)} />
      )}
    </Dialog>
  );
}
