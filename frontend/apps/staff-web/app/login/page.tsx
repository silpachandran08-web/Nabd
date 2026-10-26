"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useTranslations } from "next-intl";
import styles from "./login.module.css";
import { decodeAccessToken, landingPathFor } from "../lib/session";
import { NEW_PIN_PATTERN, lockedMinutes, needsPinChange } from "../lib/pinPolicy";
import { useHasMounted } from "../lib/useHasMounted";
import { safeReturnTo, storeTokens, takeReturnTo, signOutReason } from "../lib/apiClient";
import { LoginTimeoutError, loginFetch } from "../lib/loginFetch";
import type { Problem } from "@nabd/api-client/types";

// Matches POST /v1/auth/login's oneOf response (api/openapi.yaml) and RFC 7807 Problem errors.
type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
type MfaChallenge = { challengeId: string; method: string; expiresIn: number };
type MfaSetupRequired = { setupToken: string; expiresIn: number };
// Problem+JSON plus the login-only Retry-After hint.
type LoginProblem = Problem & { retryAfterSeconds?: number };

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

type View = "login" | "mfaVerify" | "mfaSetup" | "mfaSetupDone" | "forgotRequest" | "forgotConfirm" | "forgotDone"
  | "otpRequest" | "otpVerify" | "pinChange";

export default function LoginPage() {
  const router = useRouter();
  const t = useTranslations("login");
  const [view, setView] = useState<View>("login");
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [pin, setPin] = useState("");
  const [challenge, setChallenge] = useState<MfaChallenge | null>(null);
  const [setupRequired, setSetupRequired] = useState<MfaSetupRequired | null>(null);
  const [code, setCode] = useState("");
  const [useRecovery, setUseRecovery] = useState(false); // PLT-004: sign in with a one-time recovery code
  const [secretBase32, setSecretBase32] = useState("");
  const [otpauthUri, setOtpauthUri] = useState("");
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [slow, setSlow] = useState(false);
  const [timedOut, setTimedOut] = useState(false);

  const [mobilePhone, setMobilePhone] = useState("");
  const [resetToken, setResetToken] = useState("");
  const [newPin, setNewPin] = useState("");
  const mounted = useHasMounted();
  const signedOut = mounted && !!safeReturnTo(new URLSearchParams(window.location.search).get("returnTo"));
  const accessChanged = mounted && signOutReason() === "access_changed";

  function handleTokens(pair: TokenPair) {
    storeTokens(pair);
    const claims = decodeAccessToken(pair.accessToken);
    if (needsPinChange(claims)) { // PIN predates the 6-8 digit policy: nothing else works until it is changed
      setNewPin("");
      setFormError(null);
      setView("pinChange");
      return;
    }
    // FEF-002: back to the page the session ended on (validated same-origin path), else the role landing page.
    router.replace(takeReturnTo() ?? landingPathFor(claims?.permissions ?? [], claims?.owner === true));
  }

  async function submitPinChange(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!NEW_PIN_PATTERN.test(newPin)) { setFormError(t("errors.pinRange")); return; }
    setLoading(true);
    try {
      const res = await loginFetch(`${API_BASE}/auth/pin/change`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${localStorage.getItem("nabd_access_token")}` },
        body: JSON.stringify({ currentPin: pin, newPin }),
      }, () => setSlow(true));
      if (!res.ok) { applyProblem(res.status, (await res.json()) as LoginProblem); return; }
      // the old token still carries pinChange=true; a refresh mints one without it
      const r = await loginFetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: localStorage.getItem("nabd_refresh_token") }),
      }, () => setSlow(true));
      if (!r.ok) { setFormError(t("errors.pinChangedSignIn")); setView("login"); setPin(""); return; }
      handleTokens((await r.json()) as TokenPair);
    } catch (err) {
      if (err instanceof LoginTimeoutError) { setTimedOut(true); setFormError(t("signIn.timeout")); }
      else setFormError(t("errors.network"));
    } finally {
      setSlow(false);
      setLoading(false);
    }
  }

  async function submitLogin(e?: FormEvent) {
    e?.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setTimedOut(false);
    setLoading(true);
    try {
      const res = await loginFetch(`${API_BASE}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, email, pin }),
      }, () => setSlow(true));
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
      applyProblem(res.status, body as LoginProblem);
    } catch (err) {
      if (err instanceof LoginTimeoutError) { setTimedOut(true); setFormError(t("signIn.timeout")); }
      else setFormError(t("errors.network"));
    } finally {
      setSlow(false);
      setLoading(false);
    }
  }

  // NB-041: PIN login alone never verifies phone possession, so a staff member who only ever
  // accepted their email invite can be permanently locked out of every patient-data endpoint
  // (both email AND mobile verification are required there). This is the one flow that sets
  // mobile_verified — same triple-union response as PIN login, so it lands in the same branches.
  async function submitOtpRequest(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/otp/request`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone }),
      });
      if (!res.ok) {
        // PLT-006: unknown clinic/mobile and per-number limits are a silent 202; only a malformed phone (400) or the IP limit (429) lands here.
        const p = await res.json().catch(() => null);
        setFormError(p?.detail || t("errors.otpSend"));
        return;
      }
      setView("otpVerify");
    } catch {
      setFormError(t("errors.network"));
    } finally {
      setLoading(false);
    }
  }

  async function submitOtpVerify(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setLoading(true);
    try {
      const res = await fetch(`${API_BASE}/auth/otp/verify`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone, code }),
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
      setFormError((body as LoginProblem).detail || t("errors.codeWrong"));
    } catch {
      setFormError(t("errors.network"));
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
      const res = await fetch(`${API_BASE}/auth/mfa/${useRecovery ? "recovery-verify" : "verify"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(useRecovery
          ? { challengeToken: challenge.challengeId, recoveryCode: code.trim() }
          : { challengeId: challenge.challengeId, code }),
      });
      const body = await res.json();
      if (res.ok) {
        handleTokens(body as TokenPair);
        return;
      }
      applyProblem(res.status, body as LoginProblem);
    } catch {
      setFormError(t("errors.network"));
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
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ setupToken }), // PLT-001: challenge tokens travel in the body, never as a bearer
      });
      const body = await res.json();
      if (!res.ok) {
        setFormError((body as LoginProblem).detail || t("errors.mfaStart"));
        return;
      }
      setSecretBase32(body.secretBase32);
      setOtpauthUri(body.otpauthUri);
    } catch {
      setFormError(t("errors.network"));
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
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code, setupToken: setupRequired.setupToken }),
      });
      const body = await res.json();
      if (!res.ok) {
        applyProblem(res.status, body as LoginProblem);
        return;
      }
      setRecoveryCodes(body.recoveryCodes);
      setView("mfaSetupDone");
    } catch {
      setFormError(t("errors.network"));
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
      setFormError(t("errors.network"));
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
        const body = await res.json().catch(() => ({ title: "", detail: "" }));
        setFormError((body as LoginProblem).detail || t("errors.codeWrong"));
        return;
      }
      setView("forgotDone");
    } catch {
      setFormError(t("errors.network"));
    } finally {
      setLoading(false);
    }
  }

  function applyProblem(status: number, problem: LoginProblem) {
    if (status === 400 && problem.errors?.length) {
      setFieldErrors(Object.fromEntries(problem.errors.map((e) => [e.field, e.message])));
      return;
    }
    if (status === 423) {
      const min = lockedMinutes(problem.retryAfterSeconds);
      setFormError(min ? t("errors.locked", { min }) : t("errors.lockedLater"));
      return;
    }
    if (status === 429) {
      setFormError(t("errors.tooMany"));
      return;
    }
    // 401 deliberately stays this generic — the backend gives the same shape for
    // unknown email and wrong PIN alike, so the UI must not narrow it further.
    setFormError(problem.detail || t("errors.invalid"));
  }

  function backToLogin() {
    setView("login");
    setChallenge(null);
    setSetupRequired(null);
    setCode("");
    setPin("");
    setFormError(null);
  }

  if (view === "pinChange") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>{t("pinChange.title")}</h1>
          <p className={styles.subtitle}>{t("pinChange.subtitle")}</p>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          <form onSubmit={submitPinChange} noValidate>
            <div className={styles.field}>
              <label className={styles.label} htmlFor="cNewPin">{t("pinChange.newPin")}</label>
              <input id="cNewPin" type="password" inputMode="numeric" pattern="[0-9]{6,8}" maxLength={8} className={styles.input}
                value={newPin} onChange={(e) => setNewPin(e.target.value.replace(/\D/g, ""))} autoFocus required />
            </div>
            <button className={styles.submit} type="submit" disabled={loading}>{loading ? t("pinChange.saving") : t("pinChange.save")}</button>
          </form>
        </div>
      </main>
    );
  }

  if (view === "mfaSetup" || view === "mfaSetupDone") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>{t("mfa.setupTitle")}</h1>
          <p className={styles.subtitle}>{t("mfa.setupRole")}</p>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {view === "mfaSetup" && (
            <form onSubmit={submitMfaSetupConfirm} noValidate>
              <p className={styles.subtitle}>{t("mfa.setupSecret")}</p>
              <div className={styles.secretBox}>{secretBase32}</div>
              <a className={styles.hint} href={otpauthUri}>{t("mfa.openApp")}</a>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="setupCode">{t("mfa.code")}</label>
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
                {loading ? t("mfa.confirming") : t("mfa.confirm")}
              </button>
            </form>
          )}
          {view === "mfaSetupDone" && (
            <>
              <p className={styles.subtitle}>{t("mfa.recoverySave")}</p>
              <div className={styles.recoveryCodes}>
                {recoveryCodes.map((c) => <span key={c}>{c}</span>)}
              </div>
              <button className={styles.submit} type="button" onClick={backToLogin}>{t("mfa.signInNow")}</button>
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
          <h1 className={styles.title}>{t("mfa.verifyTitle")}</h1>
          <p className={styles.subtitle}>{useRecovery ? t("mfa.verifyRecoveryHint") : t("mfa.verifyHint")}</p>
          <form onSubmit={submitMfa} noValidate>
            {formError && <div className={styles.formError} role="alert">{formError}</div>}
            <div className={styles.field}>
              <label className={styles.label} htmlFor="code">{useRecovery ? t("mfa.recoveryCode") : t("mfa.code")}</label>
              <input
                id="code"
                className={styles.input}
                inputMode={useRecovery ? "text" : "numeric"}
                pattern={useRecovery ? undefined : "[0-9]{6}"}
                maxLength={useRecovery ? 32 : 6}
                autoFocus
                autoComplete="off"
                value={code}
                onChange={(e) => setCode(useRecovery ? e.target.value : e.target.value.replace(/\D/g, ""))}
                required
              />
            </div>
            <button className={styles.submit} type="submit" disabled={loading || (useRecovery ? code.trim().length < 6 : code.length !== 6)}>
              {loading ? t("mfa.verifying") : t("mfa.verify")}
            </button>
            <button type="button" className={styles.submit} style={{ background: "transparent", color: "inherit", border: "1px solid currentColor", marginBlockStart: 8 }}
              onClick={() => { setUseRecovery(!useRecovery); setCode(""); setFormError(null); }}>
              {useRecovery ? t("mfa.useAuthenticator") : t("mfa.useRecovery")}
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
          <h1 className={styles.title}>{t("forgot.title")}</h1>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {view === "forgotRequest" && (
            <form onSubmit={submitForgotRequest} noValidate>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fTenant">{t("forgot.clinicCode")}</label>
                <input id="fTenant" className={styles.input} value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} required />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fMobile">{t("forgot.mobile")}</label>
                <input id="fMobile" className={styles.input} value={mobilePhone} onChange={(e) => setMobilePhone(e.target.value)} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading}>{loading ? t("forgot.sending") : t("forgot.send")}</button>
            </form>
          )}
          {view === "forgotConfirm" && (
            <form onSubmit={submitForgotConfirm} noValidate>
              <p className={styles.subtitle}>{t("forgot.sent")}</p>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fToken">{t("forgot.resetCode")}</label>
                <input id="fToken" className={styles.input} value={resetToken} onChange={(e) => setResetToken(e.target.value)} required />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="fNewPin">{t("forgot.newPin")}</label>
                <input id="fNewPin" type="password" inputMode="numeric" pattern="[0-9]{6,8}" maxLength={8} className={styles.input}
                  value={newPin} onChange={(e) => setNewPin(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading}>{loading ? t("forgot.saving") : t("forgot.setPin")}</button>
            </form>
          )}
          {view === "forgotDone" && (
            <>
              <p className={styles.success}>{t("forgot.done")}</p>
              <button className={styles.submit} type="button" onClick={backToLogin}>{t("forgot.back")}</button>
            </>
          )}
          <button className={styles.linkBtn} type="button" onClick={backToLogin}>{t("forgot.back")}</button>
        </div>
      </main>
    );
  }

  if (view === "otpRequest" || view === "otpVerify") {
    return (
      <main className={styles.page}>
        <div className={styles.card}>
          <h1 className={styles.title}>{t("otp.title")}</h1>
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {view === "otpRequest" && (
            <form onSubmit={submitOtpRequest} noValidate>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="oTenant">{t("otp.clinicCode")}</label>
                <input id="oTenant" className={styles.input} value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} required />
              </div>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="oMobile">{t("otp.mobile")}</label>
                <input id="oMobile" className={styles.input} value={mobilePhone} onChange={(e) => setMobilePhone(e.target.value)} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading}>{loading ? t("otp.sending") : t("otp.send")}</button>
            </form>
          )}
          {view === "otpVerify" && (
            <form onSubmit={submitOtpVerify} noValidate>
              <p className={styles.subtitle}>{t("otp.sent")}</p>
              <div className={styles.field}>
                <label className={styles.label} htmlFor="oCode">{t("otp.code")}</label>
                <input id="oCode" className={styles.input} inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoFocus
                  value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} required />
              </div>
              <button className={styles.submit} type="submit" disabled={loading || code.length !== 6}>{loading ? t("otp.verifying") : t("otp.verify")}</button>
            </form>
          )}
          <button className={styles.linkBtn} type="button" onClick={backToLogin}>{t("forgot.back")}</button>
        </div>
      </main>
    );
  }

  return (
    <main className={styles.page}>
      <div className={styles.card}>
        <h1 className={styles.title}>{t("signIn.title")}</h1>
        <p className={styles.subtitle}>{t("signIn.subtitle")}</p>
        <form onSubmit={submitLogin} noValidate>
          {(signedOut || accessChanged) && !formError && <div className={styles.formError} role="status">{accessChanged ? t("signIn.accessChanged") : t("signIn.signedOut")}</div>}
          {formError && <div className={styles.formError} role="alert">{formError}</div>}
          {loading && slow && <p className={styles.hint} role="status">{t("signIn.slow")}</p>}
          {timedOut && !loading && <button className={`${styles.linkBtn} ${styles.retryBtn}`} type="button" onClick={() => void submitLogin()}>{t("signIn.retry")}</button>}

          <div className={styles.field}>
            <label className={styles.label} htmlFor="tenantSlug">{t("signIn.clinicCode")}</label>
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
            <label className={styles.label} htmlFor="email">{t("signIn.email")}</label>
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
            <label className={styles.label} htmlFor="pin">{t("signIn.pin")}</label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,8}"
              maxLength={8}
              className={`${styles.input} ${fieldErrors.pin ? styles.inputError : ""}`}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))}
              aria-invalid={!!fieldErrors.pin}
              required
            />
            {fieldErrors.pin && <p className={styles.fieldError}>{fieldErrors.pin}</p>}
          </div>

          <button className={styles.submit} type="submit" disabled={loading}>
            {loading ? t("signIn.submitting") : t("signIn.submit")}
          </button>
        </form>
        <button className={styles.linkBtn} type="button" onClick={() => { setFormError(null); setView("forgotRequest"); }}>
          {t("signIn.forgot")}
        </button>
        <button className={styles.linkBtn} type="button" onClick={() => { setFormError(null); setView("otpRequest"); }}>
          {t("signIn.otpInstead")}
        </button>
        <Link className={styles.linkBtn} href="/owner/login">{t("signIn.ownerLink")}</Link>
      </div>
    </main>
  );
}
