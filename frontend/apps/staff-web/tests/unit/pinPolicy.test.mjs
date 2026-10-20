// PLT-003: lock message, new-PIN pattern, forced-change claim. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { lockedMessage, needsPinChange, NEW_PIN_PATTERN } from "../../app/lib/pinPolicy.ts";

test("lock message rounds up to whole minutes and differs from the wrong-PIN text", () => {
  assert.equal(lockedMessage(1), "Too many attempts, try again in 1 min.");
  assert.equal(lockedMessage(61), "Too many attempts, try again in 2 min.");
  assert.equal(lockedMessage(1800), "Too many attempts, try again in 30 min.");
});

test("missing or bad retry seconds never print NaN", () => {
  for (const v of [undefined, null, 0, -5, NaN, "x"]) assert.equal(lockedMessage(v), "Too many attempts, try again later.");
});

test("new PIN is 6 to 8 digits only", () => {
  for (const ok of ["123456", "12345678"]) assert.ok(NEW_PIN_PATTERN.test(ok));
  for (const bad of ["12345", "123456789", "12345a", ""]) assert.ok(!NEW_PIN_PATTERN.test(bad));
});

test("forced change only when the claim is exactly true", () => {
  assert.equal(needsPinChange({ pinChange: true }), true);
  for (const c of [null, undefined, {}, { pinChange: false }]) assert.equal(needsPinChange(c), false);
});
