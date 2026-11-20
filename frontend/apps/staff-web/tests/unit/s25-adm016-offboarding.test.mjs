// ADM-016: the "Leaving Nabd?" card: request body (export or waiver), step-up targets, countdown text, error messages, owner-only render.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { REQUEST_TARGET, cancelTarget, requestBody, canSubmit, isOpen, coolingOffLeft, requestError, cancelError } = await import("../../app/lib/offboarding.ts");

test("a recent full export is named in the request; without one only a ticked waiver goes through", () => {
  assert.deepEqual(requestBody(" Closing ", "e1", false), { exportId: "e1", reason: "Closing" });
  assert.deepEqual(requestBody("Closing", null, true), { waiveExport: true, reason: "Closing" });
  assert.equal(canSubmit("Closing", "123456", null, false), false, "no export and no waiver");
  assert.equal(canSubmit("Closing", "123456", null, true), true);
  assert.equal(canSubmit("Closing", "123456", "e1", false), true);
  assert.equal(canSubmit("  ", "123456", "e1", false), false, "a reason is required");
  assert.equal(canSubmit("Closing", "12345", "e1", false), false, "six digits");
});

test("the request and each cancel have their own step-up target", () => {
  assert.equal(REQUEST_TARGET, "offboarding");
  assert.equal(cancelTarget("abc"), "offboarding-cancel:abc");
  assert.ok(cancelTarget("11111111-1111-4111-8111-111111111111").length <= 64, "fits the target limit of /auth/mfa/verify");
});

test("the countdown reads in days and hours and ends", () => {
  const now = Date.parse("2026-11-20T10:00:00Z");
  assert.equal(coolingOffLeft("2026-11-27T10:00:00Z", now), "7 days");
  assert.equal(coolingOffLeft("2026-11-26T13:30:00Z", now), "6 days 3 hours");
  assert.equal(coolingOffLeft("2026-11-21T11:00:00Z", now), "1 day 1 hour");
  assert.equal(coolingOffLeft("2026-11-20T15:10:00Z", now), "5 hours");
  assert.equal(coolingOffLeft("2026-11-20T10:20:00Z", now), "less than an hour");
  assert.equal(coolingOffLeft("2026-11-20T09:00:00Z", now), "ended");
  assert.equal(coolingOffLeft("not a date", now), "ended");
});

test("only a cooling-off or erasing request is open; errors say what to do", () => {
  assert.equal(isOpen({ status: "cooling_off" }), true);
  assert.equal(isOpen({ status: "erasing" }), true);
  assert.equal(isOpen({ status: "cancelled" }), false);
  assert.equal(isOpen(null), false);
  assert.match(requestError({ status: 409, code: "EXPORT_REQUIRED" }), /full export/);
  assert.match(requestError({ status: 403, code: "STEP_UP_REQUIRED" }), /authenticator code/);
  assert.match(requestError({ status: 403 }), /Only the clinic owner/);
  assert.match(requestError(new TypeError("x")), /Nothing was requested/);
  assert.match(cancelError({ status: 409, code: "PAST_COOLING_OFF" }), /can no longer be cancelled/);
  assert.match(cancelError(new TypeError("x")), /not cancelled/);
});

test("the card is absent unless the session is the clinic Owner, and survives a refresh of the tab", () => {
  const page = readFileSync(new URL("../../app/(clinic)/setup/data/page.tsx", import.meta.url), "utf8");
  assert.match(page, /\{canExport && <LeavingCard \/>\}/);
  assert.ok(page.indexOf("<LeavingCard") > page.indexOf("</SetupTabBody>"), "LeavingCard is rendered after </SetupTabBody>");
  const card = readFileSync(new URL("../../app/components/setup/LeavingCard.tsx", import.meta.url), "utf8");
  assert.match(card, /requestStepUpToken\(code, REQUEST_TARGET\)/);
  assert.match(card, /requestStepUpToken\(cancelCode, cancelTarget\(request\.requestId\)\)/);
  assert.match(card, /Loading…/);
  assert.match(card, /Couldn&apos;t load the deletion status/);
  assert.ok(!/fetch\(/.test(card), "goes through the api client");
});
