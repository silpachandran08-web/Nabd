import test from "node:test";
import assert from "node:assert/strict";
import { tierOptions, tierPrice, tierChip, tierToSend, windowError } from "../../app/lib/pricingTier.ts";

const line = { chargeCode: "C", name: "Consult", tier: "follow_up", unitPrice: 300, reason: "Follow-up · last visit 4 days ago", normalAmount: 500, followUpAmount: 300, emergencyAmount: 900 };

test("options list only offered tiers", () => {
  assert.deepEqual(tierOptions(line).map((o) => o.tier), ["normal", "follow_up", "emergency"]);
  assert.deepEqual(tierOptions({ ...line, followUpAmount: null, emergencyAmount: null }).map((o) => o.tier), ["normal"]);
});
test("price and chip follow the pick", () => {
  assert.equal(tierPrice(line, "emergency"), 900);
  assert.equal(tierChip(line, "follow_up"), line.reason);
  assert.match(tierChip(line, "normal"), /picked/);
});
test("tier is sent only when explicit or an emergency suggestion", () => {
  assert.equal(tierToSend(line, undefined), undefined);
  assert.equal(tierToSend({ ...line, tier: "emergency" }, undefined), "emergency");
  assert.equal(tierToSend(line, "normal"), "normal");
});
test("window bounds", () => {
  assert.equal(windowError("7"), null);
  assert.ok(windowError("0")); assert.ok(windowError("91")); assert.ok(windowError("a")); assert.ok(windowError(""));
});
