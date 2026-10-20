"use client";

import { useEffect, useState, type FormEvent } from "react";
import styles from "../login/login.module.css";

// PLT-005: the forced-enrolment step (owner and platform sign-in). The setup token only opens the
// enrol/confirm pair and travels in the body (PLT-001). onDone receives the confirm response body
// (null for a 204), so each caller decides what comes next.
type Problem = { title?: string; detail?: string };

export default function MfaEnrolCard({ enrollUrl, confirmUrl, setupToken, subtitle, onDone }: {
  enrollUrl: string;
  confirmUrl: string;
  setupToken: string;
  subtitle: string;
  onDone: (body: unknown) => void;
}) {
  const [secret, setSecret] = useState<{ secretBase32: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    fetch(enrollUrl, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ setupToken }) })
      .then(async (res) => {
        const body = await res.json().catch(() => null);
        if (!live) return;
        if (!res.ok || !body) setError((body as Problem | null)?.detail || "Couldn't start authenticator setup. Sign in again.");
        else setSecret(body);
      })
      .catch(() => live && setError("Couldn't reach the server. Check your connection and try again."));
    return () => { live = false; };
  }, [enrollUrl, setupToken, attempt]);

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await fetch(confirmUrl, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ setupToken, code }),
      });
      const body = res.status === 204 ? null : await res.json().catch(() => null);
      if (!res.ok) {
        setCode("");
        setError(res.status === 423 ? "Too many failed attempts. Try again later."
          : res.status === 401 ? "That code didn't match. Check your authenticator app and try again."
          : (body as Problem | null)?.detail || "Couldn't confirm the code.");
        return;
      }
      onDone(body);
    } catch {
      setError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <h1 className={styles.title}>Set up two-factor authentication</h1>
      <p className={styles.subtitle}>{subtitle}</p>
      {error && <div className={styles.formError} role="alert">{error}</div>}
      {!secret && !error && <p className={styles.hint} role="status">Preparing your authenticator secret…</p>}
      {!secret && error && (
        <button className={`${styles.linkBtn} ${styles.retryBtn}`} type="button" onClick={() => { setError(null); setAttempt((n) => n + 1); }}>Retry</button>
      )}
      {secret && (
        <form onSubmit={confirm} noValidate>
          <p className={styles.subtitle}>Add this secret to your authenticator app, then enter the 6-digit code it shows.</p>
          <div className={styles.secretBox} data-testid="mfa-secret">{secret.secretBase32}</div>
          <a className={styles.hint} href={secret.otpauthUri}>Open in authenticator app</a>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="setupCode">Authentication code</label>
            <input id="setupCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoFocus
              autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
          </div>
          <button className={styles.submit} type="submit" disabled={loading || code.length !== 6}>
            {loading ? "Confirming…" : "Confirm & enable"}
          </button>
        </form>
      )}
    </>
  );
}
