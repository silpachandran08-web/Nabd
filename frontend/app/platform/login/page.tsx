"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import styles from "../../login/login.module.css";

// Matches POST /v1/platform/auth/login's oneOf response and RFC 7807 Problem errors.
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type MfaChallenge = { challengeId: string; method: string; expiresIn: number };
type Problem = {
  title: string;
  detail: string;
  errors?: { field: string; message: string }[];
};

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function PlatformLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [pin, setPin] = useState("");
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const [code, setCode] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  function handleTokens(pair: TokenPair) {
    localStorage.setItem("nabd_platform_access_token", pair.accessToken);
    localStorage.setItem("nabd_platform_refresh_token", pair.refreshToken);
    router.replace("/platform");
  }

  async function submitLogin(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/platform/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, pin }),
      });
      const body = await res.json();
      if (res.ok) {
        if ("challengeId" in body) setChallenge(body as MfaChallenge);
        else handleTokens(body as TokenPair);
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
      const res = await fetch(`${API_BASE}/platform/auth/mfa/verify`, {
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

  if (challenge) {
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

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>Nabd Platform</h1>
        <p className={styles.subtitle}>Sign in with your operator email and PIN.</p>
        <form onSubmit={submitLogin} noValidate>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}

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
        <p className={styles.hint}>Platform operator access only. Not for clinic staff.</p>
      </div>
    </main>
  );
}
