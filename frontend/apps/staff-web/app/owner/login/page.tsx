"use client";

import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import styles from "../../login/login.module.css";
import { LOGIN_SLOW_MESSAGE, LOGIN_TIMEOUT_MESSAGE, LoginTimeoutError } from "../../lib/loginFetch";
import { loginCall } from "../../lib/loginCall";
import { ApiError, reasonText } from "../../lib/api";
import MfaEnrolCard from "../../components/MfaEnrolCard";

// Matches POST /v1/owners/auth/login (OwnerController) — a separate top-level identity from any
// one clinic's staff login (NB-350): this PIN is the owner's own, not tied to a tenant slug, and
// only ever unlocks a workspace list, never clinic data directly.
type PendingWorkspaceToken = { pendingToken: string; expiresIn: number };
// PLT-005: the PIN now leads to a TOTP challenge (enrolled) or forced enrolment (not enrolled);
// the workspace token exists only after the second factor.
type MfaChallenge = { challengeId: string; method: string; expiresIn: number };
type MfaSetupRequired = { setupToken: string; expiresIn: number };
type WorkspaceToken = { workspaceToken: string; expiresIn: number; remainingRecoveryCodes?: number | null };
type Step = { kind: "pin" } | { kind: "challenge"; token: string } | { kind: "setup"; token: string } | { kind: "codes"; codes: string[]; workspaceToken: string };
const OWNER_SETUP_TOKEN_KEY = "nabd_owner_setup_token"; // handed over by owner accept-invite (same key there)

export default function OwnerLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [pin, setPin] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [slow, setSlow] = useState(false);
  const [timedOut, setTimedOut] = useState(false);
  // 423 from the server (OwnerService lockout): the wireframe's "blocked" step, with Start over.
  const [blocked, setBlocked] = useState<string | null>(null);
  const [step, setStep] = useState<Step>({ kind: "pin" });
  const [code, setCode] = useState("");
  const [useRecovery, setUseRecovery] = useState(false);

  useEffect(() => {
    let t: string | null = null;
    try { t = sessionStorage.getItem(OWNER_SETUP_TOKEN_KEY); sessionStorage.removeItem(OWNER_SETUP_TOKEN_KEY); } catch { /* storage blocked */ }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (t) setStep({ kind: "setup", token: t });
  }, []);

  function enterWorkspaces(token: string) {
    sessionStorage.setItem("nabd_owner_pending_token", token);
    router.replace("/owner/workspaces");
  }

  async function submitCode(e: FormEvent) {
    e.preventDefault();
    if (step.kind !== "challenge") return;
    setFormError(null);
    setLoading(true);
    try {
      const r = await loginCall<WorkspaceToken>(`/owners/auth/mfa/${useRecovery ? "recovery-verify" : "verify"}`,
        useRecovery ? { challengeToken: step.token, recoveryCode: code } : { challengeToken: step.token, code }, () => setSlow(true));
      enterWorkspaces(r.workspaceToken);
    } catch (err) {
      if (err instanceof LoginTimeoutError) { setTimedOut(true); setFormError(LOGIN_TIMEOUT_MESSAGE); }
      else if (err instanceof ApiError && err.status === 423) setBlocked(err.detail || "Too many failed attempts. Try again later.");
      else if (err instanceof ApiError) {
        setCode("");
        setFormError(err.status === 401 ? (useRecovery ? "That recovery code isn't valid." : "That code didn't match. Try the current code.") : err.detail || "Couldn't verify the code.");
      } else setFormError(reasonText(err, ""));
    } finally {
      setSlow(false);
      setLoading(false);
    }
  }

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    setFormError(null);
    setTimedOut(false);
    setLoading(true);
    try {
      const body = await loginCall<PendingWorkspaceToken | MfaChallenge | MfaSetupRequired>("/owners/auth/login", { email, pin }, () => setSlow(true));
      setPin("");
      if ("challengeId" in body) { setCode(""); setStep({ kind: "challenge", token: body.challengeId }); return; }
      if ("setupToken" in body) { setStep({ kind: "setup", token: body.setupToken }); return; }
      enterWorkspaces(body.pendingToken);
    } catch (err) {
      if (err instanceof LoginTimeoutError) { setTimedOut(true); setFormError(LOGIN_TIMEOUT_MESSAGE); }
      else if (err instanceof ApiError && err.status === 429) setFormError("Too many attempts. Wait a minute before trying again.");
      else if (err instanceof ApiError && err.status === 423) { setPin(""); setBlocked(err.detail || "Too many failed attempts. Try again later."); }
      else if (err instanceof ApiError) { setPin(""); setFormError(err.status === 401 ? "Email or PIN is incorrect." : err.detail || "Couldn't sign you in. Try again."); }
      else setFormError(reasonText(err, ""));
    } finally {
      setSlow(false);
      setLoading(false);
    }
  }

  function startOver() {
    setBlocked(null);
    setFormError(null);
    setCode("");
    setUseRecovery(false);
    setStep({ kind: "pin" });
  }

  if (step.kind === "setup" && !blocked) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <MfaEnrolCard enrollPath="/owners/auth/mfa/enroll" confirmPath="/owners/auth/mfa/confirm"
            setupToken={step.token} subtitle="Owner accounts always use an authenticator app. Set it up once to continue."
            onDone={(b) => { const r = b as { recoveryCodes: string[]; workspaceToken: string }; setStep({ kind: "codes", codes: r.recoveryCodes, workspaceToken: r.workspaceToken }); }} />
          <button className={styles.linkBtn} type="button" onClick={startOver}>Start over</button>
        </div>
      </main>
    );
  }

  if (step.kind === "codes") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Save your recovery codes</h1>
          <p className={styles.subtitle}>Each code works once if you lose your authenticator. They won&apos;t be shown again.</p>
          <div className={styles.recoveryCodes} data-testid="recovery-codes">
            {step.codes.map((c) => <span key={c}>{c}</span>)}
          </div>
          <button className={styles.submit} type="button" onClick={() => enterWorkspaces(step.workspaceToken)}>I&apos;ve saved them, continue</button>
        </div>
      </main>
    );
  }

  if (step.kind === "challenge" && !blocked) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Enter your code</h1>
          <p className={styles.subtitle}>{useRecovery ? "One of your saved recovery codes. Each works once." : "6-digit code from your authenticator app."}</p>
          <form onSubmit={submitCode} noValidate>
            {formError && <div className={styles.formError} role="alert">{formError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="code">{useRecovery ? "Recovery code" : "Authentication code"}</label>
              <input id="code" className={styles.input} inputMode={useRecovery ? "text" : "numeric"} pattern={useRecovery ? undefined : "[0-9]{6}"}
                maxLength={useRecovery ? 32 : 6} autoFocus autoComplete="off" value={code}
                onChange={(e) => setCode(useRecovery ? e.target.value : e.target.value.replace(/\D/g, ""))} required />
            </div>
            <button className={styles.submit} type="submit" disabled={loading || (useRecovery ? code.trim().length === 0 : code.length !== 6)}>
              {loading ? "Verifying…" : "Verify"}
            </button>
          </form>
          <button className={styles.linkBtn} type="button" onClick={() => { setUseRecovery((v) => !v); setCode(""); setFormError(null); }}>
            {useRecovery ? "Use authenticator code instead" : "Use a recovery code"}
          </button>
          <button className={styles.linkBtn} type="button" onClick={startOver}>Start over</button>
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Sign in as owner</h1>
        <p className={styles.subtitle}>One login for every clinic you own — pick a workspace next.</p>
        {blocked ? (
          <div>
            <div className={styles.formError} role="alert">{blocked}</div>
            <button className={styles.submit} type="button" onClick={startOver}>
              Start over
            </button>
          </div>
        ) : (
        <form onSubmit={submit} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {loading && slow && <p className={styles.hint} role="status">{LOGIN_SLOW_MESSAGE}</p>}
          {timedOut && !loading && <button className={`${styles.linkBtn} ${styles.retryBtn}`} type="button" onClick={() => void submit()}>Retry</button>}
          <div className={styles.field}>
            <label className={styles.label} htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className={styles.input}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">PIN</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              maxLength={6}
              className={styles.input}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              required
            />
          </div>
          <button className={styles.submit} type="submit" disabled={loading}>
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>
        )}
        <p className={styles.hint}>This is your owner account, separate from any one clinic&apos;s staff login.</p>
        <Link className={styles.linkBtn} href="/login">Clinic staff? Sign in to your clinic</Link>
      </div>
    </main>
  );
}
