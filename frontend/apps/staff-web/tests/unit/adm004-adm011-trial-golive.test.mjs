import test from "node:test";
import assert from "node:assert/strict";
import { showTrialChip, trialChipText, conversionDone, conversionFailed, conversionRunning, canGoLive, openBlockers, checkText } from "../../app/lib/trial.ts";

test("chip shows only while trialing and counts days", () => {
  assert.equal(showTrialChip(null), false);
  assert.equal(showTrialChip({ status: "active" }), false);
  assert.equal(showTrialChip({ status: "trialing" }), true);
  assert.equal(trialChipText({ daysLeft: 9 }), "Trial: 9 days left");
  assert.equal(trialChipText({ daysLeft: 1 }), "Trial: 1 day left");
  assert.equal(trialChipText({ daysLeft: 0 }), "Trial ends today");
  assert.equal(trialChipText({ daysLeft: null }), "Trial");
});

test("conversion states", () => {
  assert.equal(conversionRunning({ conversion: { status: "queued" } }), true);
  assert.equal(conversionRunning({ conversion: { status: "running" } }), true);
  assert.equal(conversionFailed({ conversion: { status: "failed" } }), true);
  assert.equal(conversionDone({ status: "active", conversion: { status: "done" } }), true);
  assert.equal(conversionDone({ status: "trialing", conversion: { status: "done" } }), false);
  assert.equal(conversionRunning(null), false);
});

test("go live needs every blocker ok and at least one check", () => {
  assert.equal(canGoLive(null), false);
  assert.equal(canGoLive({ blockers: [], niceToHave: [] }), false);
  const c = { blockers: [{ code: "a", ok: true }, { code: "b", ok: false, fixUrl: "/x" }], niceToHave: [] };
  assert.equal(canGoLive(c), false);
  assert.deepEqual(openBlockers(c).map((b) => b.code), ["b"]);
  c.blockers[1].ok = true;
  assert.equal(canGoLive(c), true);
  assert.equal(checkText("price_list"), "A price list is set up");
  assert.equal(checkText("unknown_code"), "unknown_code");
});
