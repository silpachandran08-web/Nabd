"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import styles from "./account.module.css";

// E05: self-service security — MFA enrollment (NB-042), session list/revoke (NB-043), and
// emergency access (NB-048). All work against a normal logged-in access token; replacing an existing
// MFA setup and activating emergency access also need a fresh authenticator code (step-up).
type Session = { id: string; device: string | null; ip: string | null; lastSeenAt: string; current: boolean };
type Problem = { title: string; detail: string };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function AccountPage() {
  const router = useRouter();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [enrolling, setEnrolling] = useState(false);
  const [secretBase32, setSecretBase32] = useState("");
  const [code, setCode] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [mfaError, setMfaError] = useState<string | null>(null);
  const [mfaBusy, setMfaBusy] = useState(false);
  // Set when the server says this account already has MFA: re-enrolling needs the current code first.
  const [reenrollNeedsCode, setReenrollNeedsCode] = useState(false);
  const [currentCode, setCurrentCode] = useState("");

  const [bgReason, setBgReason] = useState("");
  const [bgCode, setBgCode] = useState("");
  const [bgBusy, setBgBusy] = useState(false);
  const [bgError, setBgError] = useState<string | null>(null);
  const [bgActive, setBgActive] = useState(false);

  const authedFetch = useCallback(
    async (path: string, init?: RequestInit) => {
      const token = localStorage.getItem("nabd_access_token");
      if (!token) {
        router.replace("/login");
        return null;
      }
      const res = await fetch(`${API_BASE}${path}`, {
        ...init,
        headers: { ...(init?.headers ?? {}), Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      });
      if (res.status === 401) {
        localStorage.removeItem("nabd_access_token");
        router.replace("/login");
        return null;
      }
      return res;
    },
    [router]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await authedFetch("/auth/sessions");
      if (!res) return;
      if (!res.ok) {
        setError("Couldn't load your sessions.");
        return;
      }
      setSessions(await res.json());
    } catch {
      setError("Couldn't reach the server.");
    } finally {
      setLoading(false);
    }
  }, [authedFetch]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function revoke(id: string) {
    setError(null);
    const res = await authedFetch(`/auth/sessions/${id}`, { method: "DELETE" });
    if (res && !res.ok) {
      setError("Couldn't revoke that session.");
      return;
    }
    setSessions((prev) => prev.filter((s) => s.id !== id));
  }

  // Deliberately not authedFetch: a wrong code also comes back as 401 ("mfa-failed"), which
  // authedFetch would misread as an expired session and log the user out instead of letting them retry.
  async function getStepUpToken(authCode: string): Promise<{ token: string } | { error: string }> {
    const token = localStorage.getItem("nabd_access_token");
    if (!token) {
      router.replace("/login");
      return { error: "Signed out." };
    }
    const res = await fetch(`${API_BASE}/auth/mfa/verify`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ challengeId: "", code: authCode }),
    });
    if (!res.ok) {
      const body: Problem = await res.json().catch(() => ({ title: "", detail: "That code didn't match." }));
      return { error: body.detail || "That code didn't match." };
    }
    const { stepUpToken }: { stepUpToken: string } = await res.json();
    return { token: stepUpToken };
  }

  async function startEnroll(e?: React.FormEvent) {
    e?.preventDefault();
    setMfaError(null);
    setRecoveryCodes(null);
    const headers: Record<string, string> = {};
    if (reenrollNeedsCode) {
      const stepUp = await getStepUpToken(currentCode);
      if ("error" in stepUp) {
        setMfaError(stepUp.error);
        return;
      }
      headers["X-Step-Up-Token"] = stepUp.token;
    }
    const res = await authedFetch("/auth/mfa/enroll", { method: "POST", headers });
    if (!res) return;
    if (res.status === 403 && !reenrollNeedsCode) {
      setReenrollNeedsCode(true); // already enrolled: ask for the current code, then try again
      return;
    }
    if (!res.ok) {
      setMfaError("Couldn't start setup.");
      return;
    }
    const body = await res.json();
    setSecretBase32(body.secretBase32);
    setEnrolling(true);
    setReenrollNeedsCode(false);
    setCurrentCode("");
    setCode("");
  }

  async function confirmEnroll(e: React.FormEvent) {
    e.preventDefault();
    setMfaError(null);
    setMfaBusy(true);
    try {
      const res = await authedFetch("/auth/mfa/confirm", { method: "POST", body: JSON.stringify({ code }) });
      if (!res) return;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "", detail: "That code didn't match." }));
        setMfaError(body.detail || "That code didn't match.");
        return;
      }
      const body = await res.json();
      setRecoveryCodes(body.recoveryCodes);
      setEnrolling(false);
    } finally {
      setMfaBusy(false);
    }
  }

  async function activateBreakGlass(e: React.FormEvent) {
    e.preventDefault();
    setBgError(null);
    if (!bgReason.trim()) {
      setBgError("A reason is required.");
      return;
    }
    setBgBusy(true);
    try {
      const stepUp = await getStepUpToken(bgCode);
      if ("error" in stepUp) {
        setBgError(stepUp.error);
        return;
      }
      const res = await authedFetch("/auth/break-glass/activate", {
        method: "POST",
        headers: { "X-Step-Up-Token": stepUp.token },
        body: JSON.stringify({ reason: bgReason.trim() }),
      });
      if (!res) return;
      if (!res.ok) {
        const body: Problem = await res.json().catch(() => ({ title: "", detail: "Couldn't activate emergency access." }));
        setBgError(body.detail || "Couldn't activate emergency access.");
        return;
      }
      const body = await res.json();
      localStorage.setItem("nabd_access_token", body.accessToken);
      localStorage.setItem("nabd_refresh_token", body.refreshToken);
      setBgActive(true);
      setBgReason("");
      setBgCode("");
    } finally {
      setBgBusy(false);
    }
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.title}>Account & Security</h1>
      <p className={styles.subtitle}>Manage two-factor authentication, active sessions, and emergency access.</p>

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
        ) : reenrollNeedsCode ? (
          <form onSubmit={startEnroll}>
            <p className={styles.muted} style={{ marginBottom: "8px" }}>
              Two-factor authentication is already on. Enter a code from your current authenticator to replace it.
              Your old recovery codes stop working once the new setup is confirmed.
            </p>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="currentMfaCode">Current authentication code</label>
              <input id="currentMfaCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
                value={currentCode} onChange={(e) => setCurrentCode(e.target.value.replace(/\D/g, ""))} required />
            </div>
            <button className={styles.btnPrimary} type="submit" disabled={currentCode.length !== 6}>Continue</button>
          </form>
        ) : (
          <button className={styles.btnPrimary} onClick={() => startEnroll()}>Set up two-factor authentication</button>
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
          <p className={styles.muted}>
            Emergency access activated: clinical records for 30 minutes, then it expires automatically. It is
            audited and will be reviewed afterwards.
          </p>
        ) : (
          <form onSubmit={activateBreakGlass}>
            <p className={styles.muted} style={{ marginBottom: "8px" }}>
              Gives you clinical record access for 30 minutes in an emergency. Needs the emergency-access permission,
              a reason and a fresh authenticator code. Every use is audited and reviewed afterwards.
            </p>
            {bgError && <div className={styles.formError} role="alert">{bgError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="bgReason">Reason</label>
              <textarea id="bgReason" className={styles.textarea} value={bgReason} onChange={(e) => setBgReason(e.target.value)} required />
            </div>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="bgCode">Authentication code</label>
              <input id="bgCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
                value={bgCode} onChange={(e) => setBgCode(e.target.value.replace(/\D/g, ""))} required />
            </div>
            <button className={styles.btnDanger} type="submit" disabled={bgBusy || bgCode.length !== 6}>{bgBusy ? "Activating…" : "Activate emergency access"}</button>
          </form>
        )}
      </div>
    </main>
  );
}
