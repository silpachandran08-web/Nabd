"use client";

import { useState } from "react";
import { PatientApiError, type patientApi } from "../lib/patientApi";
import type { T } from "./util";
import styles from "./linkflow.module.css";

type Step = "ask" | "enter";

/** The code is only sent when the patient taps (a link preview or a virus scanner opening the page must not burn an OTP). */
export default function OtpGate({ linkId, clinic, api, t, onVerified, onExpired }: {
  linkId: string; clinic: string; api: ReturnType<typeof patientApi>; t: T; onVerified: (token: string) => void; onExpired: () => void;
}) {
  const [step, setStep] = useState<Step>("ask");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) {
      const f = e instanceof PatientApiError ? e.failure : "network";
      if (f === "expired") onExpired();
      else setError(f === "wrong_code" ? t("otpWrong") : f === "too_many" ? t("otpTooMany") : t("errorHint"));
    } finally { setBusy(false); }
  }

  const send = () => run(async () => { await api.requestOtp(linkId); setCode(""); setStep("enter"); });
  const verify = (e: React.FormEvent) => { e.preventDefault(); void run(async () => onVerified((await api.verifyOtp(linkId, code)).accessToken)); };

  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("otpTitle")}</h1>
      {step === "ask" ? (
        <>
          <p className={styles.muted}>{t("otpHint", { clinic })}</p>
          <button type="button" className={styles.btn} disabled={busy} onClick={() => void send()}>{t("otpSend")}</button>
        </>
      ) : (
        <form className={styles.actions} onSubmit={verify}>
          <p className={styles.ok} role="status">{t("otpSent")}</p>
          <div className={styles.field}>
            <label htmlFor="otp">{t("otpLabel")}</label>
            <input id="otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} dir="ltr"
              value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
          </div>
          <button type="submit" className={styles.btn} disabled={busy || code.length !== 6}>{t("otpVerify")}</button>
          <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void send()}>{t("otpResend")}</button>
        </form>
      )}
      {error && <p className={styles.alert} role="alert">{error}</p>}
    </section>
  );
}
