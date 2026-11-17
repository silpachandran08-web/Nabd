"use client";

import { useState } from "react";
import { isCoreLink, PatientApiError, type patientApi, type Resolved } from "../lib/patientApi";
import type { T } from "./util";
import styles from "./linkflow.module.css";

type Step = "ask" | "enter";

/** The code is only sent when the patient taps (a link preview or a virus scanner opening the page must not burn an OTP). */
export default function OtpGate({ linkId, clinic, api, t, onVerified, onOpened, onExpired }: {
  linkId: string; clinic: string; api: ReturnType<typeof patientApi>; t: T; onVerified: (token: string) => void; onOpened: (resolved: Resolved) => void; onExpired: () => void;
}) {
  const core = isCoreLink(linkId); // IDC-015: Core checks the code and answers with the content in one call
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

  const send = () => run(async () => { await (core ? api.coreLinkOtp(linkId) : api.requestOtp(linkId)); setCode(""); setStep("enter"); });
  const verify = (e: React.FormEvent) => {
    e.preventDefault();
    void run(async () => { if (core) onOpened(await api.coreLinkOpen(linkId, code)); else onVerified((await api.verifyOtp(linkId, code)).accessToken); });
  };

  return (
    <section className={styles.card}>
      <h1 className={styles.h1}>{t("otpTitle")}</h1>
      {step === "ask" ? (
        <>
          <p className={styles.muted}>{clinic ? t("otpHint", { clinic }) : t("otpHintPlain")}</p>
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
