"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import styles from "./login.module.css";

// Matches POST /v1/auth/login's oneOf response (api/openapi.yaml) and RFC 7807 Problem errors.
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type MfaChallenge = { challengeId: string; method: string; expiresIn: number };
type MfaSetupRequired = { setupToken: string; expiresIn: number };
type Problem = {
  title: string;
  detail: string;
  errors?: { field: string; message: string }[];
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type View = "login" | "mfaVerify" | "mfaSetup" | "mfaSetupDone" | "forgotRequest" | "forgotConfirm" | "forgotDone";

export default function LoginPage() {
  const router = useRouter();
  const [view, setView] = useState<View>("login");
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [pin, setPin] = useState("");
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const [setupRequired, setSetupRequired] = useState<MfaSetupRequired | null>(null);
  const [code, setCode] = useState("");
  const [secretBase32, setSecretBase32] = useState("");
  const [otpauthUri, setOtpauthUri] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [mobilePhone, setMobilePhone] = useState("");
  const [resetToken, setResetToken] = useState("");
  const [newPin, setNewPin] = useState("");

  function handleTokens(pair: TokenPair) {
    localStorage.setItem("nabd_access_token", pair.accessToken);
    localStorage.setItem("nabd_refresh_token", pair.refreshToken);
    router.replace("/setup");
  }

  async function submitLogin(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, email, pin }),
      });
      const body = await res.json();
      if (res.ok) {
        if ("setupToken" in body) {
          setSetupRequired(body as MfaSetupRequired);
          setView("mfaSetup");
          await startEnrollment((body as MfaSetupRequired).setupToken);
        } else if ("challengeId" in body) {
          setChallenge(body as MfaChallenge);
          setView("mfaVerify");
        } else {
          handleTokens(body as TokenPair);
        }
        return;
      }
      applyProblem(res.status, body as Problem);
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  async function submitMfa(e: FormEvent) {
    e.preventDefault();
    if (!challenge) return;
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/mfa/verify`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ challengeId: challenge.challengeId, code }),
      });
      const body = await res.json();
      if (res.ok) {
        handleTokens(body as TokenPair);
        return;
      }
      applyProblem(res.status, body as Problem);
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  // NB-042: this staff member's role requires MFA and they haven't enrolled yet — the setupToken
  // authorizes exactly enroll+confirm, nothing else.
  async function startEnrollment(setupToken: string) {
    setFormError(null);
    try {
      const res = await fetch(`${API_BASE}/auth/mfa/enroll`, {
        method: "POST",
        headers: { Authorization: `Bearer ${setupToken}` },
      });
      const body = await res.json();
      if (!res.ok) {
        setFormError((body as Problem).detail || "Couldn't start MFA setup.");
        return;
      }
      setSecretBase32(body.secretBase32);
      setOtpauthUri(body.otpauthUri);
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    }
  }

  async function submitMfaSetupConfirm(e: FormEvent) {
    e.preventDefault();
    if (!setupRequired) return;
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/mfa/confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${setupRequired.setupToken}` },
        body: JSON.stringify({ code }),
      });
      const body = await res.json();
      if (!res.ok) {
        applyProblem(res.status, body as Problem);
        return;
      }
      setRecoveryCodes(body.recoveryCodes);
      setView("mfaSetupDone");
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  async function submitForgotRequest(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLoading(true);
    try {
      await fetch(`${API_BASE}/auth/pin/reset-request`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone }),
      });
      // always proceeds the same way, known account or not — no enumeration
      setView("forgotConfirm");
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  async function submitForgotConfirm(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/pin/reset-confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone, token: resetToken, newPin }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ title: "", detail: "That code is incorrect or has expired." }));
        setFormError((body as Problem).detail || "That code is incorrect or has expired.");
        return;
      }
      setView("forgotDone");
    } catch {
      setFormError("Couldn't reach the server. Check your connection and try again.");
    } finally {
      setLoading(false);
    }
  }

  function applyProblem(status: number, problem: Problem) {
    if (status === 400 && problem.errors?.length) {
      setFieldErrors(Object.fromEntries(problem.errors.map((e) => [e.field, e.message])));
      return;
    }
    if (status === 423) {
      setFormError("Too many failed attempts. Try again later.");
      return;
    }
    if (status === 429) {
      setFormError("Too many attempts. Try again shortly.");
      return;
    }
    // 401 deliberately stays this generic — the backend gives the same shape for
    // unknown email and wrong PIN alike, so the UI must not narrow it further.
    setFormError(problem.detail || "Invalid credentials.");
  }

  function backToLogin() {
    setView("login");
    setChallenge(null);
    setSetupRequired(null);
    setCode("");
    setPin("");
    setFormError(null);
  }

  if (view === "mfaSetup" || view === "mfaSetupDone") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Set up two-factor authentication</h1>
          <p className={styles.subtitle}>Your role requires this before you can sign in.</p>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {view === "mfaSetup" && (
            <form onSubmit={submitMfaSetupConfirm} noValidate>
              <p className={styles.subtitle}>Add this secret to your authenticator app, then enter the 6-digit code it shows.</p>
              <div className={styles.secretBox}>{secretBase32}</div>
              <a className={styles.hint} href={otpauthUri}>Open in authenticator app</a>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="setupCode">Authentication code</label>
                <input
                  id="setupCode"
                  className={styles.input}
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  autoFocus
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                  required
                />
              </div>
              <button className={styles.submit} type="submit" disabled={loading || code.length !== 6}>
                {loading ? "Confirming…" : "Confirm & enable"}
              </button>
            </form>
          )}
          {view === "mfaSetupDone" && (
            <>
              <p className={styles.subtitle}>Save these one-time recovery codes somewhere safe — each works once if you lose your authenticator.</p>
              <div className={styles.recoveryCodes}>
                {recoveryCodes.map((c) => <span key={c}>{c}</span>)}
              </div>
              <button className={styles.submit} type="button" onClick={backToLogin}>Sign in now</button>
            </>
          )}
        </div>
      </main>
    );
  }

  if (view === "mfaVerify" && challenge) {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Enter your code</h1>
          <p className={styles.subtitle}>6-digit code from your authenticator app.</p>
          <form onSubmit={submitMfa} noValidate>
            {formError && <div className={styles.formError} role="alert">{formError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="code">Authentication code</label>
              <input
                id="code"
                className={styles.input}
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                autoFocus
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                required
              />
            </div>
            <button className={styles.submit} type="submit" disabled={loading || code.length !== 6}>
              {loading ? "Verifying…" : "Verify"}
            </button>
          </form>
        </div>
      </main>
    );
  }

  if (view === "forgotRequest" || view === "forgotConfirm" || view === "forgotDone") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>Reset your PIN</h1>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {view === "forgotRequest" && (
            <form onSubmit={submitForgotRequest} noValidate>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fTenant">Clinic code</label>
                <input id="fTenant" className={styles.input} value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} required />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fMobile">Mobile number</label>
                <input id="fMobile" className={styles.input} value={mobilePhone} onChange={(e) => setMobilePhone(e.target.value)} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading}>{loading ? "Sending…" : "Send reset code"}</button>
            </form>
          )}
          {view === "forgotConfirm" && (
            <form onSubmit={submitForgotConfirm} noValidate>
              <p className={styles.subtitle}>If that number is registered, a reset code was sent to it.</p>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fToken">Reset code</label>
                <input id="fToken" className={styles.input} value={resetToken} onChange={(e) => setResetToken(e.target.value)} required />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fNewPin">New PIN</label>
                <input id="fNewPin" type="password" inputMode="numeric" pattern="[0-9]{4,6}" maxLength={6} className={styles.input}
                  value={newPin} onChange={(e) => setNewPin(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading}>{loading ? "Saving…" : "Set new PIN"}</button>
            </form>
          )}
          {view === "forgotDone" && (
            <>
              <p className={styles.success}>Your PIN has been reset.</p>
              <button className={styles.submit} type="button" onClick={backToLogin}>Back to sign in</button>
            </>
          )}
          <button className={styles.linkBtn} type="button" onClick={backToLogin}>Back to sign in</button>
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Sign in to Nabd</h1>
        <p className={styles.subtitle}>Enter your clinic, email and PIN.</p>
        <form onSubmit={submitLogin} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}

          <div className={styles.field}>
            <label className={styles.label} htmlFor="tenantSlug">Clinic code</label>
            <input
              id="tenantSlug"
              className={`${styles.input} ${fieldErrors.tenantSlug ? styles.inputError : ""}`}
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              aria-invalid={!!fieldErrors.tenantSlug}
              required
            />
            {fieldErrors.tenantSlug && <p className={styles.fieldError}>{fieldErrors.tenantSlug}</p>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="email">Email</label>
            <input
              id="email"
              type="email"
              className={`${styles.input} ${fieldErrors.email ? styles.inputError : ""}`}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={!!fieldErrors.email}
              required
            />
            {fieldErrors.email && <p className={styles.fieldError}>{fieldErrors.email}</p>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="pin">PIN</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              maxLength={6}
              className={`${styles.input} ${fieldErrors.pin ? styles.inputError : ""}`}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              aria-invalid={!!fieldErrors.pin}
              required
            />
            {fieldErrors.pin && <p className={styles.fieldError}>{fieldErrors.pin}</p>}
          </div>

          <button className={styles.submit} type="submit" disabled={loading}>
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>
        <button className={styles.linkBtn} type="button" onClick={() => { setFormError(null); setView("forgotRequest"); }}>
          Forgot PIN?
        </button>
        <p className={styles.hint}>WhatsApp OTP sign-in isn&apos;t built yet.</p>
      </div>
    </main>
  );
}
