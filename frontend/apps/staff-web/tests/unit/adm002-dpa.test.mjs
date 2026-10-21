// ADM-002: DPA step helpers (blocker wording, re-accept banner, who sees signing controls, OTP shape).
import { test } from "node:test";
import assert from "node:assert/strict";
import { blockerText, otpValid, reacceptBanner, roleWord, showSigning } from "../../app/lib/dpa.ts";

const base = { version: "2026-10-v1", marketCode: "IN", html: "", accepted: null, dataGate: "demo_only", blockers: ["DPA_REQUIRED", "PLAN_REQUIRED"], reacceptDueBy: null };

test("blockers read as actions; unknown codes pass through", () => {
  assert.equal(blockerText("PLAN_REQUIRED"), "Choose a plan");
  assert.equal(blockerText("DPA_REQUIRED"), "Sign the data processing agreement");
  assert.equal(blockerText("X"), "X");
});

test("India says data fiduciary, the Gulf markets controller", () => {
  assert.equal(roleWord("IN"), "data fiduciary");
  for (const m of ["KSA", "AE", "QA"]) assert.equal(roleWord(m), "controller");
});

test("banner only for a signed clinic with a newer DPA due; never for a new clinic", () => {
  assert.equal(reacceptBanner(null), null);
  assert.equal(reacceptBanner(base), null);
  const live = { ...base, dataGate: "live", blockers: [], accepted: { version: "2026-01-v0", at: "2026-02-01T00:00:00Z", by: "A", method: "otp", acceptanceId: "x" }, reacceptDueBy: "2026-11-20T00:00:00Z" };
  assert.match(reacceptBanner(live), /2026-10-v1.*2026-11-20.*Nothing is blocked/);
  assert.equal(reacceptBanner({ ...live, reacceptDueBy: null }), null);
});

test("signing controls: absent without setup:edit, absent once the current version is signed", () => {
  assert.equal(showSigning(null, true), false);
  assert.equal(showSigning(base, false), false);
  assert.equal(showSigning(base, true), true);
  assert.equal(showSigning({ ...base, accepted: { version: "2026-10-v1" } }, true), false);
  assert.equal(showSigning({ ...base, accepted: { version: "2026-01-v0" } }, true), true);
});

test("otp must be exactly 6 digits", () => {
  for (const v of ["123456", "000000"]) assert.equal(otpValid(v), true);
  for (const v of ["", "12345", "1234567", "12a456", " 12345"]) assert.equal(otpValid(v), false);
});
