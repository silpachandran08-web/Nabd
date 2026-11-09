// FEF-032 AC3: the staff sign-in state machine (app/(auth)/login/loginMachine.ts) is a pure reducer; every action and every
// view change is covered here: PIN sign-in, MFA verify / recovery code / enrolment, WhatsApp OTP, forgot PIN, forced PIN
// change, field errors, lockout (423), rate limit (429), network / timeout errors and Back. The clinic code (tenantSlug) is
// the workspace the user signs in to; the screen has no separate workspace picker (the owner picker is /owner/login).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { initialLogin, loginReducer, problemAction, signInAction } from "../../app/(auth)/login/loginMachine.ts";

const t = (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key);
const run = (actions, from = initialLogin) => actions.reduce(loginReducer, from);
const CHALLENGE = { challengeId: "ch-1", method: "totp", expiresIn: 300 };
const SETUP = { setupToken: "st-1", expiresIn: 300 };
const TOKENS = { accessToken: "a", refreshToken: "r", expiresIn: 900 };

test("starts on the PIN sign-in view with an empty, idle form", () => {
  assert.equal(initialLogin.view, "login");
  assert.equal(initialLogin.loading, false);
  assert.deepEqual(initialLogin.fieldErrors, {});
  assert.equal(initialLogin.formError, null);
});

test("field: each input writes only its own value", () => {
  for (const name of ["tenantSlug", "email", "pin", "mobilePhone", "resetToken", "newPin", "code"]) {
    const s = loginReducer(initialLogin, { type: "field", name, value: "x1" });
    assert.equal(s[name], "x1", name);
    assert.deepEqual({ ...s, [name]: initialLogin[name] }, initialLogin, `${name} changed nothing else`);
  }
});

test("PIN sign-in: submit clears errors and the timeout, slow, settled; the reducer never mutates its input", () => {
  const dirty = { ...initialLogin, formError: "old", fieldErrors: { pin: "bad" }, timedOut: true };
  const frozen = JSON.stringify(dirty);
  const s = loginReducer(dirty, { type: "submit", login: true });
  assert.equal(JSON.stringify(dirty), frozen);
  assert.deepEqual([s.loading, s.formError, s.fieldErrors, s.timedOut], [true, null, {}, false]);
  const other = loginReducer(dirty, { type: "submit" }); // OTP / MFA / forgot submits keep field errors and the timeout flag
  assert.deepEqual([other.loading, other.formError, other.fieldErrors, other.timedOut], [true, null, { pin: "bad" }, true]);
  const slow = loginReducer(s, { type: "slow" });
  assert.equal(slow.slow, true);
  assert.deepEqual([loginReducer(slow, { type: "settled" }).loading, loginReducer(slow, { type: "settled" }).slow], [false, false]);
});

test("PIN sign-in answers: tokens (caller signs in), MFA challenge, MFA setup required", () => {
  assert.equal(signInAction(TOKENS), null);
  const verify = run([signInAction(CHALLENGE)]);
  assert.equal(verify.view, "mfaVerify");
  assert.deepEqual(verify.challenge, CHALLENGE);
  const setup = run([signInAction(SETUP)]);
  assert.equal(setup.view, "mfaSetup");
  assert.deepEqual(setup.setupRequired, SETUP);
});

test("MFA verify: switch to a recovery code and back clears the code and the error", () => {
  const s = run([signInAction(CHALLENGE), { type: "field", name: "code", value: "123456" }, { type: "error", message: "wrong" }, { type: "toggleRecovery" }]);
  assert.deepEqual([s.useRecovery, s.code, s.formError, s.view], [true, "", null, "mfaVerify"]);
  assert.equal(loginReducer(s, { type: "toggleRecovery" }).useRecovery, false);
});

test("MFA enrolment: secret shown, confirmed with recovery codes, then Sign in now goes back to the PIN form", () => {
  let s = run([{ type: "field", name: "pin", value: "123456" }, signInAction(SETUP), { type: "error", message: "stale" }, { type: "enrolStarted" }]);
  assert.equal(s.formError, null);
  s = run([{ type: "enrolSecret", secretBase32: "ABC", otpauthUri: "otpauth://x" }, { type: "mfaEnrolled", recoveryCodes: ["r1", "r2"] }], s);
  assert.deepEqual([s.secretBase32, s.otpauthUri, s.recoveryCodes, s.view], ["ABC", "otpauth://x", ["r1", "r2"], "mfaSetupDone"]);
  s = loginReducer(s, { type: "back" });
  assert.deepEqual([s.view, s.setupRequired, s.challenge, s.pin, s.code, s.formError], ["login", null, null, "", "", null]);
});

test("WhatsApp OTP: open from sign-in, code sent, then the same three answers as PIN sign-in; Back returns", () => {
  let s = run([{ type: "error", message: "old" }, { type: "open", view: "otpRequest" }]);
  assert.deepEqual([s.view, s.formError], ["otpRequest", null]);
  s = loginReducer(s, { type: "otpSent" });
  assert.equal(s.view, "otpVerify");
  assert.equal(loginReducer(s, signInAction(CHALLENGE)).view, "mfaVerify");
  assert.equal(loginReducer(s, signInAction(SETUP)).view, "mfaSetup");
  assert.equal(loginReducer(s, { type: "back" }).view, "login");
});

test("forgot PIN: request -> confirm -> done -> back, never revealing whether the account exists", () => {
  let s = run([{ type: "open", view: "forgotRequest" }]);
  assert.equal(s.view, "forgotRequest");
  s = loginReducer(s, { type: "forgotSent" });
  assert.equal(s.view, "forgotConfirm");
  s = loginReducer(s, { type: "forgotDone" });
  assert.equal(s.view, "forgotDone");
  assert.equal(loginReducer(s, { type: "back" }).view, "login");
});

test("forced PIN change: new PIN cleared, error cleared; a failed refresh sends the user back to sign in", () => {
  let s = run([{ type: "field", name: "newPin", value: "99" }, { type: "error", message: "x" }, { type: "pinChangeRequired" }]);
  assert.deepEqual([s.view, s.newPin, s.formError], ["pinChange", "", null]);
  s = run([{ type: "field", name: "pin", value: "1234" }, { type: "pinChangedSignIn", message: "errors.pinChangedSignIn" }], s);
  assert.deepEqual([s.view, s.pin, s.formError], ["login", "", "errors.pinChangedSignIn"]);
});

test("errors: 400 field errors, 423 lockout with and without Retry-After, 429, server detail, generic 401", () => {
  const fields = problemAction(400, { title: "", errors: [{ field: "pin", message: "6 to 8 digits" }] }, t);
  assert.deepEqual(loginReducer(initialLogin, fields).fieldErrors, { pin: "6 to 8 digits" });
  assert.equal(loginReducer(initialLogin, problemAction(423, { title: "", retryAfterSeconds: 125 }, t)).formError, 'errors.locked:{"min":3}');
  assert.equal(loginReducer(initialLogin, problemAction(423, { title: "" }, t)).formError, "errors.lockedLater");
  assert.equal(loginReducer(initialLogin, problemAction(429, { title: "", detail: "x" }, t)).formError, "errors.tooMany");
  assert.equal(problemAction(401, { title: "", detail: "Invalid credentials" }, t).message, "Invalid credentials");
  assert.equal(problemAction(401, { title: "" }, t).message, "errors.invalid");
  assert.equal(problemAction(400, { title: "", errors: [] }, t).type, "error", "a 400 without field errors is a form error");
});

test("network and timeout: a timeout also offers Retry (timedOut), the next sign-in clears it", () => {
  const s = run([{ type: "submit", login: true }, { type: "timeout", message: "signIn.timeout" }, { type: "settled" }]);
  assert.deepEqual([s.timedOut, s.formError, s.loading], [true, "signIn.timeout", false]);
  assert.equal(loginReducer(s, { type: "submit", login: true }).timedOut, false);
  assert.equal(run([{ type: "error", message: "errors.network" }]).formError, "errors.network");
});

test("every action type of the union has its own case, and each returns a state", () => {
  const src = readFileSync(new URL("../../app/(auth)/login/loginMachine.ts", import.meta.url), "utf8");
  const union = [...src.slice(src.indexOf("export type LoginAction"), src.indexOf("export const initialLogin")).matchAll(/type: "(\w+)"/g)].map((m) => m[1]);
  const cases = [...src.matchAll(/case "(\w+)":/g)].map((m) => m[1]);
  assert.equal(union.length, 20);
  assert.deepEqual([...cases].sort(), [...union].sort());
  for (const type of union) assert.ok(loginReducer(initialLogin, { type, name: "pin", value: "", view: "otpRequest", message: "", errors: {}, body: SETUP, secretBase32: "", otpauthUri: "", recoveryCodes: [] }), type);
});
