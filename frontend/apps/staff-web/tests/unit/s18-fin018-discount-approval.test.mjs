// FIN-018: desk banner / owner inbox rules and the SSE mapping. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { bannerApproval, needsApproval, toMinorUnits, fromMinor, thresholdInput, thresholdValue } from "../../app/components/checkout/discountApproval.ts";
import { resourcesFor } from "../../../../packages/api-client/sse/patch.ts";

const a = (billStatus) => ({ approvalId: "a1", billStatus });

test("the banner shows the newest request only while it matters", () => {
  assert.equal(bannerApproval([]), null);
  for (const s of ["awaiting_approval", "approved", "rejected"]) assert.equal(bannerApproval([a(s), a("issued")])?.billStatus, s);
  for (const s of ["issued", "expired"]) assert.equal(bannerApproval([a(s), a("awaiting_approval")]), null);
});

test("a checkout 409 asks for approval only for APPROVAL_REQUIRED", () => {
  assert.equal(needsApproval({ code: "APPROVAL_REQUIRED" }), true);
  assert.equal(needsApproval({ code: "AWAITING_APPROVAL" }), false);
  assert.equal(needsApproval(undefined), false);
});

test("money crosses as exact minor units", () => {
  assert.equal(toMinorUnits("0.1"), 10);
  assert.equal(toMinorUnits("1234.56"), 123456);
  assert.equal(toMinorUnits("19.99"), 1999);
  assert.equal(toMinorUnits("1.234"), null);
  assert.equal(toMinorUnits("-1"), null);
  assert.equal(fromMinor(7500), 75);
});

test("threshold policy values: empty is off, amount in minor units, percent 0-100", () => {
  assert.equal(thresholdValue("amount", ""), "off");
  assert.equal(thresholdValue("amount", "50"), "5000");
  assert.equal(thresholdValue("amount", "abc"), null);
  assert.equal(thresholdValue("percent", "10"), "10");
  assert.equal(thresholdValue("percent", "100"), "100");
  assert.equal(thresholdValue("percent", "101"), null);
  assert.equal(thresholdInput("amount", "5000"), "50");
  assert.equal(thresholdInput("percent", "off"), "");
  assert.equal(thresholdInput("amount", undefined), "");
});

test("approval events refresh the approvals screens; the desk subscribes to the billing topic", () => {
  assert.deepEqual(resourcesFor("billing.discount.approval_decided.v1"), ["discount-approvals"]);
  assert.deepEqual(resourcesFor("billing.discount.approval_requested.v1"), ["discount-approvals"]);
  const live = readFileSync(new URL("../../app/lib/live.tsx", import.meta.url), "utf8");
  assert.match(live, /billing:view"\)\) t\.push\("billing"\)/);
});

test("banner controls are absent without the grant, and the PIN is cleared after each attempt", () => {
  const banner = readFileSync(new URL("../../app/components/checkout/ApprovalBanner.tsx", import.meta.url), "utf8");
  assert.match(banner, /\{canPin && !pinOpen && <button/);
  assert.match(banner, /\{canPayLater && !approval\.payLater && <button/);
  assert.match(banner, /finally \{\s*setPin\(""\)/);
  const page = readFileSync(new URL("../../app/(clinic)/checkout/[queueEntryId]/page.tsx", import.meta.url), "utf8");
  assert.match(page, /canPin=\{canApprove\}/);
  assert.match(page, /discountApprovalId: approval\.approvalId/);
});

test("fix round: the PIN entry names its approval, and the counter sale has the same approval flow", () => {
  const banner = readFileSync(new URL("../../app/components/checkout/ApprovalBanner.tsx", import.meta.url), "utf8");
  assert.match(banner, /"\/auth\/pin-assertion", \{ pin, approvalId: approval\.approvalId \}/);
  const otc = readFileSync(new URL("../../app/(clinic)/checkout/otc/page.tsx", import.meta.url), "utf8");
  assert.match(otc, /needsApproval\(e\.problem\)/);
  assert.match(otc, /"\/billing\/discount-approvals", req/);
  assert.match(otc, /<ApprovalBanner approval=\{approval\}[^>]*canPin=\{canApprove\} canPayLater=\{false\}/);
  assert.match(otc, /discountApprovalId: approval\.approvalId/);
});
