// FEF-032: the staff sign-in screen as a pure state machine. The page sends the requests and dispatches what came
// back; every view change and form-state change happens here, so tests/unit/s19-fef032-login-reducer.test.mjs covers
// each transition (PIN, MFA verify / recovery / enrol, WhatsApp OTP, forgot PIN, forced PIN change, errors, lockout, back).
import type { Problem } from "@nabd/api-client/types";
import { lockedMinutes } from "../../lib/pinPolicy.ts";

// Matches POST /v1/auth/login's oneOf response (api/openapi.yaml) and RFC 7807 Problem errors.
export type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };
export type MfaChallenge = { challengeId: string; method: string; expiresIn: number };
export type MfaSetupRequired = { setupToken: string; expiresIn: number };
// Problem+JSON plus the login-only Retry-After hint.
export type LoginProblem = Problem & { retryAfterSeconds?: number };

export type View = "login" | "mfaVerify" | "mfaSetup" | "mfaSetupDone" | "forgotRequest" | "forgotConfirm" | "forgotDone"
  | "otpRequest" | "otpVerify" | "pinChange";

export type LoginState = {
  view: View;
  tenantSlug: string; email: string; pin: string; mobilePhone: string; resetToken: string; newPin: string; code: string;
  challenge: MfaChallenge | null; setupRequired: MfaSetupRequired | null;
  useRecovery: boolean; // PLT-004: sign in with a one-time recovery code
  secretBase32: string; otpauthUri: string; recoveryCodes: string[];
  fieldErrors: Record<string, string>; formError: string | null;
  loading: boolean; slow: boolean; timedOut: boolean;
};

export type Field = "tenantSlug" | "email" | "pin" | "mobilePhone" | "resetToken" | "newPin" | "code";

export type LoginAction =
  | { type: "field"; name: Field; value: string }
  | { type: "open"; view: "forgotRequest" | "otpRequest" } // links under the sign-in form
  | { type: "submit"; login?: boolean } // a request starts; the PIN sign-in also clears field errors and the timeout
  | { type: "slow" } // the sign-in request is taking long
  | { type: "settled" } // the request finished, whatever the outcome
  | { type: "error"; message: string }
  | { type: "timeout"; message: string }
  | { type: "fieldErrors"; errors: Record<string, string> }
  | { type: "mfaSetupRequired"; body: MfaSetupRequired } // PIN or OTP sign-in: the role needs MFA and none is enrolled
  | { type: "enrolStarted" }
  | { type: "enrolSecret"; secretBase32: string; otpauthUri: string }
  | { type: "mfaChallenge"; body: MfaChallenge } // PIN or OTP sign-in: an authenticator code is needed
  | { type: "toggleRecovery" }
  | { type: "mfaEnrolled"; recoveryCodes: string[] }
  | { type: "pinChangeRequired" } // signed in with a PIN that predates the 6-8 digit policy
  | { type: "pinChangedSignIn"; message: string } // the PIN changed but the refresh failed: sign in again
  | { type: "otpSent" }
  | { type: "forgotSent" }
  | { type: "forgotDone" }
  | { type: "back" };

export const initialLogin: LoginState = {
  view: "login", tenantSlug: "", email: "", pin: "", mobilePhone: "", resetToken: "", newPin: "", code: "",
  challenge: null, setupRequired: null, useRecovery: false, secretBase32: "", otpauthUri: "", recoveryCodes: [],
  fieldErrors: {}, formError: null, loading: false, slow: false, timedOut: false,
};

export function loginReducer(s: LoginState, a: LoginAction): LoginState {
  switch (a.type) {
    case "field": return { ...s, [a.name]: a.value };
    case "open": return { ...s, formError: null, view: a.view };
    case "submit": return a.login ? { ...s, formError: null, fieldErrors: {}, timedOut: false, loading: true } : { ...s, formError: null, loading: true };
    case "slow": return { ...s, slow: true };
    case "settled": return { ...s, slow: false, loading: false };
    case "error": return { ...s, formError: a.message };
    case "timeout": return { ...s, timedOut: true, formError: a.message };
    case "fieldErrors": return { ...s, fieldErrors: a.errors };
    case "mfaSetupRequired": return { ...s, setupRequired: a.body, view: "mfaSetup" };
    case "enrolStarted": return { ...s, formError: null };
    case "enrolSecret": return { ...s, secretBase32: a.secretBase32, otpauthUri: a.otpauthUri };
    case "mfaChallenge": return { ...s, challenge: a.body, view: "mfaVerify" };
    case "toggleRecovery": return { ...s, useRecovery: !s.useRecovery, code: "", formError: null };
    case "mfaEnrolled": return { ...s, recoveryCodes: a.recoveryCodes, view: "mfaSetupDone" };
    case "pinChangeRequired": return { ...s, newPin: "", formError: null, view: "pinChange" };
    case "pinChangedSignIn": return { ...s, formError: a.message, view: "login", pin: "" };
    case "otpSent": return { ...s, view: "otpVerify" };
    case "forgotSent": return { ...s, view: "forgotConfirm" };
    case "forgotDone": return { ...s, view: "forgotDone" };
    case "back": return { ...s, view: "login", challenge: null, setupRequired: null, code: "", pin: "", formError: null };
  }
}

type T = (key: string, values?: Record<string, string | number>) => string;

/** A refused sign-in / MFA call: field errors (400), lockout (423), rate limit (429), else the server's words or the generic line. */
export function problemAction(status: number, problem: LoginProblem, t: T): LoginAction {
  if (status === 400 && problem.errors?.length) return { type: "fieldErrors", errors: Object.fromEntries(problem.errors.map((e) => [e.field, e.message])) };
  if (status === 423) {
    const min = lockedMinutes(problem.retryAfterSeconds);
    return { type: "error", message: min ? t("errors.locked", { min }) : t("errors.lockedLater") };
  }
  if (status === 429) return { type: "error", message: t("errors.tooMany") };
  // 401 deliberately stays this generic — the backend gives the same shape for
  // unknown email and wrong PIN alike, so the UI must not narrow it further.
  return { type: "error", message: problem.detail || t("errors.invalid") };
}

/** The three answers of PIN / OTP sign-in: enrol MFA first, verify a code, or tokens (null: the caller signs in). */
export function signInAction(body: TokenPair | MfaChallenge | MfaSetupRequired): LoginAction | null {
  if ("setupToken" in body) return { type: "mfaSetupRequired", body };
  if ("challengeId" in body) return { type: "mfaChallenge", body };
  return null;
}
