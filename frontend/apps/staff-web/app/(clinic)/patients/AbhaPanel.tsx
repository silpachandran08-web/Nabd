"use client";

import { useState } from "react";
import styles from "./patients.module.css";
import { api, ApiError } from "../../lib/api";
import { isAbhaId, isAbhaNumber, maskAbha, stripAbhaNumber } from "@nabd/domain";

// IDC-017: verify or create an ABHA for a just-registered patient. Optional, skippable, nothing is sent to ABDM until
// the receptionist starts a step. The Aadhaar number lives only in this component's state for the init call.
type Res = { txnId?: string; verified?: boolean; abhaLast4?: string; abhaAddress?: string };
type Mode = "verify" | "aadhaar" | "mobile";
type Existing = { id: string; name: string; mrn?: string };

export default function AbhaPanel({ patientId, onDone, onOpenExisting }: {
  patientId: string; onDone: () => void; onOpenExisting: (id: string) => void;
}) {
  const [mode, setMode] = useState<Mode>("verify");
  const [value, setValue] = useState("");
  const [otp, setOtp] = useState("");
  const [txnId, setTxnId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [existing, setExisting] = useState<Existing | null>(null);
  const [done, setDone] = useState<Res | null>(null);

  const inputOk = mode === "verify" ? isAbhaId(value) : mode === "aadhaar" ? /^\d{12}$/.test(stripAbhaNumber(value)) : /^\d{10}$/.test(value.trim());

  async function call(step: "init" | "confirm") {
    if (busy) return;
    setBusy(true); setError(""); setExisting(null);
    try {
      const v = value.trim();
      const res = mode === "verify"
        ? await api.post<Res>(`/patients/${patientId}/abha/verify`, step === "init"
          ? { step, abhaNumberOrAddress: isAbhaNumber(v) ? stripAbhaNumber(v) : v } : { step, otp, txnId })
        : await api.post<Res>("/abha/enrol", step === "init"
          ? { step, mode, ...(mode === "aadhaar" ? { aadhaarNumber: stripAbhaNumber(v) } : { mobile: v }) }
          : { step, mode, otp, txnId, patientId });
      if (step === "init") setTxnId(res.txnId ?? null);
      else { setDone(res); setValue(""); setOtp(""); }
      if (step === "init") setValue(mode === "aadhaar" ? "" : value); // never keep the Aadhaar number once sent
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.problem.existingPatient) setExisting(e.problem.existingPatient as Existing);
      else if (e instanceof ApiError && e.status === 409) setError("ABDM is not switched on for this clinic.");
      else if (e instanceof ApiError && e.status === 422) setError("ABHA or OTP not accepted. Check it and try again.");
      else if (e instanceof ApiError && e.status === 503) setError("ABDM is not reachable right now. Registration is saved; you can add the ABHA later.");
      else setError("Couldn't complete that step. Try again.");
    } finally { setBusy(false); }
  }

  if (done) {
    return (
      <div role="status">
        <p className={styles.dialogSub}>ABHA verified: {maskAbha(done.abhaLast4 ?? "")}{done.abhaAddress ? ` · ${done.abhaAddress}` : ""}</p>
        <div className={styles.dialogActions}><button type="button" className={styles.primaryBtn} onClick={onDone}>Done</button></div>
      </div>
    );
  }
  return (
    <div>
      <p className={styles.dialogSub}>Patient registered. ABHA is optional. Skip it if the patient declines.</p>
      <div className={styles.fieldRow}>
        {([["verify", "Verify existing"], ["aadhaar", "Create with Aadhaar OTP"], ["mobile", "Create with mobile OTP"]] as [Mode, string][]).map(([m, l]) => (
          <button key={m} type="button" className={m === mode ? styles.primaryBtn : styles.secondaryBtn} disabled={!!txnId}
            onClick={() => { setMode(m); setValue(""); setError(""); setExisting(null); }}>{l}</button>
        ))}
      </div>
      <label className={styles.fieldLabel} htmlFor="abha-value">
        {mode === "verify" ? "ABHA number or address" : mode === "aadhaar" ? "Aadhaar number (not saved)" : "Mobile number"}
      </label>
      <input id="abha-value" className={styles.input} value={value} disabled={!!txnId} autoComplete="off" inputMode={mode === "verify" ? "text" : "numeric"}
        onChange={(e) => setValue(e.target.value)} />
      {txnId && (
        <>
          <label className={styles.fieldLabel} htmlFor="abha-otp">OTP the patient received</label>
          <input id="abha-otp" className={styles.input} value={otp} inputMode="numeric" autoComplete="one-time-code" maxLength={8} onChange={(e) => setOtp(e.target.value)} />
        </>
      )}
      {error && <p role="alert" className={styles.errorState}>{error}</p>}
      {existing && (
        <div role="alert" className={styles.candidateRow}>
          <span>This ABHA is already linked to <span className={styles.patientName}>{existing.name}</span>{existing.mrn ? <span className={styles.mrn}>{existing.mrn}</span> : null}</span>
          <button type="button" className={styles.secondaryBtn} onClick={() => onOpenExisting(existing.id)}>Open record</button>
          <span className={styles.dialogSub}>To combine the two records, use Duplicate review.</span>
        </div>
      )}
      <div className={styles.dialogActions}>
        <button type="button" className={styles.secondaryBtn} onClick={onDone}>Skip ABHA</button>
        {txnId
          ? <button type="button" className={styles.primaryBtn} disabled={busy || otp.trim().length < 4} onClick={() => void call("confirm")}>{busy ? "Verifying…" : "Confirm OTP"}</button>
          : <button type="button" className={styles.primaryBtn} disabled={busy || !inputOk} onClick={() => void call("init")}>{busy ? "Sending…" : "Send OTP"}</button>}
      </div>
    </div>
  );
}
