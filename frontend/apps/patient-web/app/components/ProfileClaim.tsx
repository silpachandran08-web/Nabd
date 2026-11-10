"use client";

import { useEffect, useMemo, useState } from "react";
import { patientApi, PatientApiError, type KeepScope, type ProfileClaim as Claim } from "../lib/patientApi";
import { formatWhen, makeT } from "./util";
import styles from "./linkflow.module.css";

const KEEP: { value: KeepScope; label: string; hint?: string }[] = [
  { value: "NONE", label: "claimKeepNone" },
  { value: "BOOK_ONLY", label: "proxyScopeBook", hint: "proxyScopeBookHint" },
  { value: "BOOK_AND_RECORDS", label: "proxyScopeRecords", hint: "proxyScopeRecordsHint" },
];

type Phase = "loading" | "expired" | "error" | "ask" | "enter" | "done";

/** IDC-012: the young adult opens the claim link, proves their own phone with a code and chooses what the former guardian may still
 * do. Nothing is pre-selected except "nothing": guardian access ends unless they choose otherwise. */
export default function ProfileClaim({ token, messages, locale }: { token: string | null; messages: Record<string, string>; locale: string }) {
  const t = useMemo(() => makeT(messages), [messages]);
  const api = useMemo(() => patientApi(), []);
  const [phase, setPhase] = useState<Phase>(token ? "loading" : "expired");
  const [claim, setClaim] = useState<Claim | null>(null);
  const [keep, setKeep] = useState<KeepScope>("NONE");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    let live = true;
    api.profileClaim(token).then((c) => { if (live) { setClaim(c); setPhase("ask"); } })
      .catch((e) => { if (live) setPhase(e instanceof PatientApiError && e.failure === "expired" ? "expired" : "error"); });
    return () => { live = false; };
  }, [api, token]);

  const opens = claim ? formatWhen(`${claim.opensOn}T00:00:00`, locale) : "";

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (e) {
      const f = e instanceof PatientApiError ? e.failure : "network";
      if (f === "expired") setPhase("expired");
      else setError(f === "wrong_code" ? t("otpWrong") : f === "too_many" ? t("otpTooMany") : f === "not_open" ? t("claimNotOpen", { date: opens })
        : f === "own_phone" ? t("claimOwnPhone") : t("errorHint"));
    } finally { setBusy(false); }
  }

  // The code is sent only on a tap: a link preview opening the page must not send an OTP.
  const send = () => run(async () => { await api.profileClaimOtp(token!); setCode(""); setPhase("enter"); });
  const submit = (e: React.FormEvent) => { e.preventDefault(); void run(async () => { await api.claimProfile(token!, code, keep); setPhase("done"); }); };

  return (
    <main className={styles.page} aria-busy={phase === "loading"}>
      <header className={styles.bar}><p className={styles.clinic}>{claim?.clinicName ?? t("title")}</p></header>
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
      {(phase === "ask" || phase === "enter") && claim && (
        <section className={styles.card}>
          <h1 className={styles.h1}>{t("claimTitle", { name: claim.firstName })}</h1>
          <p className={styles.muted}>{t("claimIntro", { clinic: claim.clinicName, guardian: claim.guardianFirstName })}</p>
          {!claim.ownPhone ? (
            <p className={styles.alert} role="status">{t("claimOwnPhone")}</p>
          ) : phase === "ask" ? (
            <button type="button" className={styles.btn} disabled={busy} onClick={() => void send()}>{t("otpSend")}</button>
          ) : (
            <form className={styles.actions} onSubmit={submit}>
              <fieldset className={styles.field}>
                <legend>{t("claimKeepLabel", { guardian: claim.guardianFirstName })}</legend>
                {KEEP.map((s) => (
                  <label key={s.value} className={styles.field}>
                    <span><input type="radio" name="keep" value={s.value} checked={keep === s.value} onChange={() => setKeep(s.value)} /> {t(s.label)}</span>
                    {s.hint && <span className={styles.muted}>{t(s.hint)}</span>}
                  </label>
                ))}
              </fieldset>
              <p className={styles.ok} role="status">{t("otpSent")}</p>
              <div className={styles.field}>
                <label htmlFor="otp">{t("otpLabel")}</label>
                <input id="otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} dir="ltr"
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button type="submit" className={styles.btn} disabled={busy || code.length !== 6}>{t("claimSubmit")}</button>
              <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void send()}>{t("otpResend")}</button>
            </form>
          )}
          {error && <p className={styles.alert} role="alert">{error}</p>}
        </section>
      )}
      {phase === "done" && (
        <section className={styles.card}>
          <p className={styles.ok} role="status">{t("claimDone")}</p>
        </section>
      )}
    </main>
  );
}
