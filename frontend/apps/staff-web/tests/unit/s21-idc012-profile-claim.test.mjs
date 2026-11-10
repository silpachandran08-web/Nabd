// IDC-012: the "Turning 18" worklist filter and the desk claim error messages.
import { test } from "node:test";
import assert from "node:assert/strict";
const { reviewsPath, canClaimAtDesk, claimError } = await import("../../app/lib/profileClaim.ts");

test("the worklist asks for due or overdue explicitly", () => {
  assert.equal(reviewsPath("due"), "/patients/guardian-reviews-due?status=due");
  assert.equal(reviewsPath("overdue"), "/patients/guardian-reviews-due?status=overdue");
});

test("only an overdue (already 18) row can be claimed at the desk", () => {
  assert.equal(canClaimAtDesk("overdue"), true);
  assert.equal(canClaimAtDesk("due"), false);
});

test("problem codes map to what the receptionist should do next", () => {
  assert.match(claimError({ status: 422, code: "own-phone-required" }, "send"), /own on file/);
  assert.match(claimError({ status: 409, code: "claim-not-open" }, "send"), /not 18 yet/);
  assert.match(claimError({ status: 401, code: "otp-invalid" }, "claim"), /wrong or has expired/);
  assert.match(claimError({ status: 429 }, "send"), /30 seconds/);
  assert.match(claimError({ status: 500 }, "claim"), /Guardian access is unchanged/);
  assert.match(claimError(new TypeError("fetch failed"), "send"), /Couldn't reach the server/);
});
