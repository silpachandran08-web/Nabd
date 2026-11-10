"use client";

import { useCallback, useEffect, useState } from "react";
import styles from "./account.module.css";
import { storeTokens } from "../../lib/apiClient";
import { api, reasonText } from "../../lib/api";
import ThemeSwitch from "../../shell/ThemeSwitch";

// E05: self-service security — MFA enrollment (NB-042), session list/revoke (NB-043), and
// self-activated emergency access (NB-048). All work against a normal logged-in access token.
type Session = { id: string; device: string | null; ip: string | null; lastSeenAt: string; current: boolean };

export default function AccountPage() {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [enrolling, setEnrolling] = useState(false);
  const [secretBase32, setSecretBase32] = useState("");
  const [code, setCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [mfaError, setMfaError] = useState<string | null>(null);
  const [mfaBusy, setMfaBusy] = useState(false);

  const [bgReason, setBgReason] = useState("");
  const [bgBusy, setBgBusy] = useState(false);
  const [bgError, setBgError] = useState<string | null>(null);
  const [bgActive, setBgActive] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSessions(await api.get<Session[]>("/auth/sessions"));
    } catch (e) {
      setError(reasonText(e, "Couldn't load your sessions."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function revoke(id: string) {
    setError(null);
    try {
      await api.delete(`/auth/sessions/${id}`);
    } catch (e) {
      setError(reasonText(e, "Couldn't revoke that session."));
      return;
    }
    setSessions((prev) => prev.filter((s) => s.id !== id));
  }

  async function startEnroll() {
    setMfaError(null);
    setRecoveryCodes(null);
    let body: { secretBase32: string };
    try {
      body = await api.post<{ secretBase32: string }>("/auth/mfa/enroll");
    } catch (e) {
      setMfaError(reasonText(e, "Couldn't start setup."));
      return;
    }
    setSecretBase32(body.secretBase32);
    setEnrolling(true);
    setCode("");
  }

  async function confirmEnroll(e: React.FormEvent) {
    e.preventDefault();
    setMfaError(null);
    setMfaBusy(true);
    try {
      const body = await api.post<{ recoveryCodes: string[] }>("/auth/mfa/confirm", { code });
      setRecoveryCodes(body.recoveryCodes);
      setEnrolling(false);
    } catch (e) {
      setMfaError(reasonText(e, "That code didn't match."));
    } finally {
      setMfaBusy(false);
    }
  }

  async function activateBreakGlass(e: React.FormEvent) {
    e.preventDefault();
    setBgError(null);
    if (bgReason.trim().length < 10) {
      setBgError("Please describe the emergency (at least 10 characters).");
      return;
    }
    setBgBusy(true);
    try {
      storeTokens(await api.post<Parameters<typeof storeTokens>[0]>("/auth/break-glass/activate", { reasonCode: "other", reasonText: bgReason.trim() }));
      setBgActive(true);
      setBgReason("");
    } catch (e) {
      setBgError(reasonText(e, "Couldn't activate emergency access."));
    } finally {
      setBgBusy(false);
    }
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Account & Security</h1>
      <p className={styles.subtitle}>Manage two-factor authentication, active sessions, and emergency access.</p>

      {/* FEF-025: same choice as the user menu */}
      <div className={styles.card}><ThemeSwitch variant="page" /></div>

      <h2 className={styles.sectionTitle}>Two-factor authentication</h2>
      <div className={styles.card}>
        {mfaError && <div className={styles.formError} role="alert">{mfaError}</div>}
        {recoveryCodes ? (
          <>
            <p className={styles.muted} style={{ marginBottom: "8px" }}>
              Two-factor authentication is enabled. Save these one-time recovery codes — each works once.
            </p>
            <div className={styles.recoveryCodes}>{recoveryCodes.map((c) => <span key={c}>{c}</span>)}</div>
          </>
        ) : enrolling ? (
          <form onSubmit={confirmEnroll}>
            <p className={styles.muted} style={{ marginBottom: "8px" }}>Add this secret to your authenticator app, then enter the code it shows.</p>
            <div className={styles.secretBox}>{secretBase32}</div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="mfaCode">Authentication code</label>
              <input id="mfaCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
                value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
            </div>
            <button className={styles.btnPrimary} type="submit" disabled={mfaBusy || code.length !== 6}>
              {mfaBusy ? "Confirming…" : "Confirm & enable"}
            </button>
          </form>
        ) : (
          <button className={styles.btnPrimary} onClick={startEnroll}>Set up two-factor authentication</button>
        )}
      </div>

      <h2 className={styles.sectionTitle}>Active sessions</h2>
      <div className={styles.card}>
        {loading ? (
          <div className={styles.state}>Loading…</div>
        ) : error ? (
          <div className={styles.state}>{error}</div>
        ) : sessions.length === 0 ? (
          <div className={styles.state}>No active sessions.</div>
        ) : (
          sessions.map((s) => (
            <div className={styles.row} key={s.id}>
              <div>
                <div>{s.device || "Unknown device"} {s.current && <span className={styles.pill}>This device</span>}</div>
                <div className={styles.muted}>{s.ip} · last seen {new Date(s.lastSeenAt).toLocaleString()}</div>
              </div>
              {!s.current && <button className={styles.btnDanger} onClick={() => revoke(s.id)}>Revoke</button>}
            </div>
          ))
        )}
      </div>

      <h2 className={styles.sectionTitle}>Emergency access</h2>
      <div className={styles.card}>
        {bgActive ? (
          <p className={styles.muted}>Emergency access activated. It expires automatically in 30 minutes and the owner has been notified.</p>
        ) : (
          <form onSubmit={activateBreakGlass}>
            <p className={styles.muted} style={{ marginBottom: "8px" }}>
              Temporarily elevates your access for 30 minutes. Requires a reason and is always audited.
            </p>
            {bgError && <div className={styles.formError} role="alert">{bgError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="bgReason">Reason</label>
              <textarea id="bgReason" className={styles.textarea} value={bgReason} onChange={(e) => setBgReason(e.target.value)} required />
            </div>
            <button className={styles.btnDanger} type="submit" disabled={bgBusy}>{bgBusy ? "Activating…" : "Activate emergency access"}</button>
          </form>
        )}
      </div>
    </main>
  );
}
