"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useTranslations } from "next-intl";
import styles from "../login/login.module.css";
import { publicApi, ApiError, reasonText } from "../lib/api";

// PLT-005: the forced-enrolment step (owner and platform sign-in). The setup token only opens the
// enrol/confirm pair and travels in the body (PLT-001). onDone receives the confirm response body
// (null for a 204), so each caller decides what comes next.

export default function MfaEnrolCard({ enrollPath, confirmPath, setupToken, subtitle, onDone }: {
  enrollPath: string; // paths under the API base, e.g. "/auth/mfa/enroll"
  confirmPath: string;
  setupToken: string;
  subtitle: string;
  onDone: (body: unknown) => void;
}) {
  const t = useTranslations("login");
  const [secret, setSecret] = useState<{ secretBase32: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    publicApi.post<{ secretBase32: string; otpauthUri: string }>(enrollPath, { setupToken }, { keepSession: true })
      .then((body) => { if (live) setSecret(body); })
      .catch((e) => { if (live) setError(e instanceof ApiError ? e.detail || t("enrol.fail") : reasonText(e, "")); });
    return () => { live = false; };
  }, [enrollPath, setupToken, attempt, t]);

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      onDone(await publicApi.post<unknown>(confirmPath, { setupToken, code }, { keepSession: true }) ?? null);
    } catch (err) {
      if (!(err instanceof ApiError)) { setError(reasonText(err, "")); return; }
      setCode("");
      setError(err.status === 423 ? t("enrol.locked")
        : err.status === 401 ? t("enrol.mismatch")
        : err.detail || t("enrol.confirmFail"));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <h1 className={styles.title}>{t("mfa.setupTitle")}</h1>
      <p className={styles.subtitle}>{subtitle}</p>
      {error && <div className={styles.formError} role="alert">{error}</div>}
      {!secret && !error && <p className={styles.hint} role="status">{t("enrol.preparing")}</p>}
      {!secret && error && (
        <button className={`${styles.linkBtn} ${styles.retryBtn}`} type="button" onClick={() => { setError(null); setAttempt((n) => n + 1); }}>{t("signIn.retry")}</button>
      )}
      {secret && (
        <form onSubmit={confirm} noValidate>
          <p className={styles.subtitle}>{t("mfa.setupSecret")}</p>
          <div className={styles.secretBox} data-testid="mfa-secret">{secret.secretBase32}</div>
          <a className={styles.hint} href={secret.otpauthUri}>{t("mfa.openApp")}</a>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="setupCode">{t("mfa.code")}</label>
            <input id="setupCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoFocus
              autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
          </div>
          <button className={styles.submit} type="submit" disabled={loading || code.length !== 6}>
            {loading ? t("mfa.confirming") : t("mfa.confirm")}
          </button>
        </form>
      )}
    </>
  );
}
