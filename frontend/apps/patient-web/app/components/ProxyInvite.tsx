"use client";

import { useEffect, useMemo, useState } from "react";
import { patientApi, PatientApiError, type ProxyInvite as Invite, type ProxyScope } from "../lib/patientApi";
import { makeT } from "./util";
import styles from "./linkflow.module.css";

const SCOPES: { value: ProxyScope; label: string; hint: string }[] = [
  { value: "BOOK_ONLY", label: "proxyScopeBook", hint: "proxyScopeBookHint" },
  { value: "BOOK_AND_RECORDS", label: "proxyScopeRecords", hint: "proxyScopeRecordsHint" },
  { value: "FULL", label: "proxyScopeFull", hint: "proxyScopeFullHint" },
];

type Phase = "loading" | "expired" | "error" | "ask" | "enter" | "done";

/** IDC-009: the family member opens the link from WhatsApp, proves the phone with a code and picks what the caregiver may do. Book only is pre-selected. */
export default function ProxyInvite({ token, messages }: { token: string | null; messages: Record<string, string> }) {
  const t = useMemo(() => makeT(messages), [messages]);
  const api = useMemo(() => patientApi(), []);
  const [phase, setPhase] = useState<Phase>(token ? "loading" : "expired");
  const [invite, setInvite] = useState<Invite | null>(null);
  const [scope, setScope] = useState<ProxyScope>("BOOK_ONLY");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    let live = true;
    api.proxyInvite(token).then((i) => { if (live) { setInvite(i); setPhase("ask"); } })
      .catch((e) => { if (live) setPhase(e instanceof PatientApiError && e.failure === "expired" ? "expired" : "error"); });
    return () => { live = false; };
  }, [api, token]);

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) {
      const f = e instanceof PatientApiError ? e.failure : "network";
      if (f === "expired") setPhase("expired");
      else setError(f === "wrong_code" ? t("otpWrong") : f === "too_many" ? t("otpTooMany") : t("errorHint"));
    } finally { setBusy(false); }
  }

  // The code is sent only on a tap: a link preview opening the page must not send an OTP.
  const send = () => run(async () => { await api.proxyOtp(token!); setCode(""); setPhase("enter"); });
  const accept = (e: React.FormEvent) => { e.preventDefault(); void run(async () => { await api.proxyAccept(token!, code, scope); setPhase("done"); }); };

  return (
    <main className={styles.page} aria-busy={phase === "loading"}>
      <header className={styles.bar}><p className={styles.clinic}>{invite?.clinicName ?? t("title")}</p></header>
      {phase === "loading" && <div className={styles.skel} role="status" aria-label={t("loading")} />}
      {phase === "expired" && (
        <section className={styles.card}>
          <h1 className={styles.h1}>{t("expiredTitle")}</h1>
          <p className={styles.muted}>{t("expiredHint")}</p>
        </section>
      )}
      {phase === "error" && (
        <section className={styles.card} role="alert">
          <h1 className={styles.h1}>{t("errorTitle")}</h1>
          <p className={styles.muted}>{t("errorHint")}</p>
          <button type="button" className={styles.btn} onClick={() => window.location.reload()}>{t("retry")}</button>
        </section>
      )}
      {(phase === "ask" || phase === "enter") && invite && (
        <section className={styles.card}>
          <h1 className={styles.h1}>{t("proxyTitle", { name: invite.caregiverFirstName })}</h1>
          <p className={styles.muted}>{t("proxyIntro", { name: invite.caregiverFirstName, relation: invite.relation.replace(/_/g, " "), clinic: invite.clinicName })}</p>
          {phase === "ask" ? (
            <button type="button" className={styles.btn} disabled={busy} onClick={() => void send()}>{t("otpSend")}</button>
          ) : (
            <form className={styles.actions} onSubmit={accept}>
              <fieldset className={styles.field}>
                <legend>{t("proxyScopeLabel")}</legend>
                {SCOPES.map((s) => (
                  <label key={s.value} className={styles.field}>
                    <span><input type="radio" name="scope" value={s.value} checked={scope === s.value} onChange={() => setScope(s.value)} /> {t(s.label)}</span>
                    <span className={styles.muted}>{t(s.hint)}</span>
                  </label>
                ))}
              </fieldset>
              <p className={styles.ok} role="status">{t("otpSent")}</p>
              <div className={styles.field}>
                <label htmlFor="otp">{t("otpLabel")}</label>
                <input id="otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} dir="ltr"
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button type="submit" className={styles.btn} disabled={busy || code.length !== 6}>{t("proxyAccept")}</button>
              <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void send()}>{t("otpResend")}</button>
            </form>
          )}
          {error && <p className={styles.alert} role="alert">{error}</p>}
        </section>
      )}
      {phase === "done" && invite && (
        <section className={styles.card}>
          <p className={styles.ok} role="status">{t("proxyDone", { name: invite.caregiverFirstName })}</p>
        </section>
      )}
    </main>
  );
}
