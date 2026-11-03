"use client";

// FEF-032: the staff sign-in screen. The state machine is loginMachine.ts (a tested reducer); the views are LoginViews.tsx;
// this page sends the requests and dispatches their outcome.
import { useReducer, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { decodeAccessToken, landingPathFor } from "../../lib/session";
import { NEW_PIN_PATTERN, needsPinChange } from "../../lib/pinPolicy";
import { useHasMounted } from "../../lib/useHasMounted";
import { safeReturnTo, storeTokens, takeReturnTo, signOutReason } from "../../lib/apiClient";
import { LoginTimeoutError, loginFetch } from "../../lib/loginFetch";
import { initialLogin, loginReducer, problemAction, signInAction, type LoginProblem, type MfaChallenge, type MfaSetupRequired, type TokenPair } from "./loginMachine";
import { ForgotView, MfaSetupView, MfaVerifyView, OtpView, PinChangeView, SignInView } from "./LoginViews";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/v1";

export default function LoginPage() {
  const router = useRouter();
  const t = useTranslations("login");
  const [s, dispatch] = useReducer(loginReducer, initialLogin);
  const { view, tenantSlug, email, pin, challenge, setupRequired, code, useRecovery, mobilePhone, resetToken, newPin } = s;
  const mounted = useHasMounted();
  const signedOut = mounted && !!safeReturnTo(new URLSearchParams(window.location.search).get("returnTo"));
  const accessChanged = mounted && signOutReason() === "access_changed";
  const slow = () => dispatch({ type: "slow" });
  const failed = (err: unknown) => dispatch(err instanceof LoginTimeoutError ? { type: "timeout", message: t("signIn.timeout") } : { type: "error", message: t("errors.network") });
  const applyProblem = (status: number, problem: LoginProblem) => dispatch(problemAction(status, problem, t));

  function handleTokens(pair: TokenPair) {
    storeTokens(pair);
    const claims = decodeAccessToken(pair.accessToken);
    if (needsPinChange(claims)) { // PIN predates the 6-8 digit policy: nothing else works until it is changed
      dispatch({ type: "pinChangeRequired" });
      return;
    }
    // FEF-002: back to the page the session ended on (validated same-origin path), else the role landing page.
    router.replace(takeReturnTo() ?? landingPathFor(claims?.permissions ?? [], claims?.owner === true));
  }

  /** PIN and OTP sign-in answer the same three ways: enrol MFA, verify a code, or tokens. */
  async function signedIn(body: TokenPair | MfaChallenge | MfaSetupRequired) {
    const next = signInAction(body);
    if (!next) return handleTokens(body as TokenPair);
    dispatch(next);
    if (next.type === "mfaSetupRequired") await startEnrollment(next.body.setupToken);
  }

  async function submitPinChange(e: FormEvent) {
    e.preventDefault();
    if (!NEW_PIN_PATTERN.test(newPin)) { dispatch({ type: "error", message: t("errors.pinRange") }); return; }
    dispatch({ type: "submit" });
    try {
      const res = await loginFetch(`${API_BASE}/auth/pin/change`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${localStorage.getItem("nabd_access_token")}` },
        body: JSON.stringify({ currentPin: pin, newPin }),
      }, slow);
      if (!res.ok) { applyProblem(res.status, (await res.json()) as LoginProblem); return; }
      // the old token still carries pinChange=true; a refresh mints one without it
      const r = await loginFetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: localStorage.getItem("nabd_refresh_token") }),
      }, slow);
      if (!r.ok) { dispatch({ type: "pinChangedSignIn", message: t("errors.pinChangedSignIn") }); return; }
      handleTokens((await r.json()) as TokenPair);
    } catch (err) {
      failed(err);
    } finally {
      dispatch({ type: "settled" });
    }
  }

  async function submitLogin(e?: FormEvent) {
    e?.preventDefault();
    dispatch({ type: "submit", login: true });
    try {
      const res = await loginFetch(`${API_BASE}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, email, pin }),
      }, slow);
      const body = await res.json();
      if (res.ok) { await signedIn(body); return; }
      applyProblem(res.status, body as LoginProblem);
    } catch (err) {
      failed(err);
    } finally {
      dispatch({ type: "settled" });
    }
  }

  // NB-041: PIN login alone never verifies phone possession, so a staff member who only ever
  // accepted their email invite can be permanently locked out of every patient-data endpoint
  // (both email AND mobile verification are required there). This is the one flow that sets
  // mobile_verified — same triple-union response as PIN login, so it lands in the same branches.
  async function submitOtpRequest(e: FormEvent) {
    e.preventDefault();
    dispatch({ type: "submit" });
    try {
      const res = await fetch(`${API_BASE}/auth/otp/request`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone }),
      });
      if (!res.ok) {
        // PLT-006: unknown clinic/mobile and per-number limits are a silent 202; only a malformed phone (400) or the IP limit (429) lands here.
        const p = await res.json().catch(() => null);
        dispatch({ type: "error", message: p?.detail || t("errors.otpSend") });
        return;
      }
      dispatch({ type: "otpSent" });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  async function submitOtpVerify(e: FormEvent) {
    e.preventDefault();
    dispatch({ type: "submit" });
    try {
      const res = await fetch(`${API_BASE}/auth/otp/verify`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone, code }),
      });
      const body = await res.json();
      if (res.ok) { await signedIn(body); return; }
      dispatch({ type: "error", message: (body as LoginProblem).detail || t("errors.codeWrong") });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  async function submitMfa(e: FormEvent) {
    e.preventDefault();
    if (!challenge) return;
    dispatch({ type: "submit" });
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
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  // NB-042: this staff member's role requires MFA and they haven't enrolled yet — the setupToken
  // authorizes exactly enroll+confirm, nothing else.
  async function startEnrollment(setupToken: string) {
    dispatch({ type: "enrolStarted" });
    try {
      const res = await fetch(`${API_BASE}/auth/mfa/enroll`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ setupToken }), // PLT-001: challenge tokens travel in the body, never as a bearer
      });
      const body = await res.json();
      if (!res.ok) {
        dispatch({ type: "error", message: (body as LoginProblem).detail || t("errors.mfaStart") });
        return;
      }
      dispatch({ type: "enrolSecret", secretBase32: body.secretBase32, otpauthUri: body.otpauthUri });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    }
  }

  async function submitMfaSetupConfirm(e: FormEvent) {
    e.preventDefault();
    if (!setupRequired) return;
    dispatch({ type: "submit" });
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
      dispatch({ type: "mfaEnrolled", recoveryCodes: body.recoveryCodes });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  async function submitForgotRequest(e: FormEvent) {
    e.preventDefault();
    dispatch({ type: "submit" });
    try {
      await fetch(`${API_BASE}/auth/pin/reset-request`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone }),
      });
      // always proceeds the same way, known account or not — no enumeration
      dispatch({ type: "forgotSent" });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  async function submitForgotConfirm(e: FormEvent) {
    e.preventDefault();
    dispatch({ type: "submit" });
    try {
      const res = await fetch(`${API_BASE}/auth/pin/reset-confirm`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantSlug, mobilePhone, token: resetToken, newPin }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ title: "", detail: "" }));
        dispatch({ type: "error", message: (body as LoginProblem).detail || t("errors.codeWrong") });
        return;
      }
      dispatch({ type: "forgotDone" });
    } catch {
      dispatch({ type: "error", message: t("errors.network") });
    } finally {
      dispatch({ type: "settled" });
    }
  }

  if (view === "pinChange") return <PinChangeView s={s} dispatch={dispatch} onSubmit={submitPinChange} />;
  if (view === "mfaSetup" || view === "mfaSetupDone") return <MfaSetupView s={s} dispatch={dispatch} onSubmit={submitMfaSetupConfirm} />;
  if (view === "mfaVerify" && challenge) return <MfaVerifyView s={s} dispatch={dispatch} onSubmit={submitMfa} />;
  if (view === "forgotRequest" || view === "forgotConfirm" || view === "forgotDone") {
    return <ForgotView s={s} dispatch={dispatch} onRequest={submitForgotRequest} onConfirm={submitForgotConfirm} />;
  }
  if (view === "otpRequest" || view === "otpVerify") return <OtpView s={s} dispatch={dispatch} onRequest={submitOtpRequest} onVerify={submitOtpVerify} />;
  return <SignInView s={s} dispatch={dispatch} onSubmit={submitLogin} onRetry={() => void submitLogin()} signedOut={signedOut} accessChanged={accessChanged} />;
}
