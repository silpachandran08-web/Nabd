"use client";

import { useEffect, useMemo, useState } from "react";
import { patientApi, PatientApiError, type ExportLink } from "../lib/patientApi";
import { formatWhen, makeT } from "./util";
import styles from "./linkflow.module.css";

type Phase = "loading" | "expired" | "error" | "ask" | "enter" | "done";

/** ADM-015: the owner's data export link. Each download needs a code sent to the clinic owner's mobile; the file is handed to the
 * browser as a download and never kept in storage. */
export default function ExportDownload({ token, messages, locale }: { token: string | null; messages: Record<string, string>; locale: string }) {
  const t = useMemo(() => makeT(messages), [messages]);
  const api = useMemo(() => patientApi(), []);
  const [phase, setPhase] = useState<Phase>(token ? "loading" : "expired");
  const [link, setLink] = useState<ExportLink | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    let live = true;
    api.exportLink(token).then((l) => { if (live) { setLink(l); setPhase("ask"); } })
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
  const send = () => run(async () => { await api.exportOtp(token!); setCode(""); setPhase("enter"); });
  const download = (e: React.FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const blob = await api.exportDownload(token!, code);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "nabd-export.zip";
      a.click();
      URL.revokeObjectURL(url);
      setPhase("done");
    });
  };

  return (
    <main className={styles.page} aria-busy={phase === "loading"}>
      <header className={styles.bar}><p className={styles.clinic}>{link?.clinicName ?? t("title")}</p></header>
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
      {(phase === "ask" || phase === "enter") && link && (
        <section className={styles.card}>
          <h1 className={styles.h1}>{t("exportTitle")}</h1>
          <p className={styles.muted}>{t("exportIntro", { date: formatWhen(link.expiresAt, locale, { time: true }), left: String(link.downloadsLeft) })}</p>
          {phase === "ask" ? (
            <button type="button" className={styles.btn} disabled={busy} onClick={() => void send()}>{t("otpSend")}</button>
          ) : (
            <form className={styles.actions} onSubmit={download}>
              <p className={styles.ok} role="status">{t("otpSent")}</p>
              <div className={styles.field}>
                <label htmlFor="otp">{t("otpLabel")}</label>
                <input id="otp" className={styles.input} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} dir="ltr"
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button type="submit" className={styles.btn} disabled={busy || code.length !== 6}>{t("exportDownload")}</button>
              <button type="button" className={styles.btnGhost} disabled={busy} onClick={() => void send()}>{t("otpResend")}</button>
            </form>
          )}
          {error && <p className={styles.alert} role="alert">{error}</p>}
        </section>
      )}
      {phase === "done" && (
        <section className={styles.card}>
          <p className={styles.ok} role="status">{t("exportDone")}</p>
          <p className={styles.muted}>{t("exportChecksum")}</p>
        </section>
      )}
    </main>
  );
}
