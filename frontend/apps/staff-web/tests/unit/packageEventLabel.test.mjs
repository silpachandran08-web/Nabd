// COM-005 / COM-044: ledger labels and the delivery tick. A blocked send never reads as sent. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { deliveryLabel, eventLabel } from "../../app/(clinic)/packages/eventLabel.ts";

test("reminder event names the tier", () => {
  assert.equal(eventLabel("expiry_warning_sent", "30-day reminder (desk)"), "Expiry reminder (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "7-day: recorded only, no message sent"), "Expiry reminder (7-day)"); // legacy note
  for (const n of [null, undefined, "", "x"]) assert.equal(eventLabel("expiry_warning_sent", n), "Expiry reminder");
});
test("COM-044 message events have plain labels", () => {
  assert.equal(eventLabel("card_requested", "Package card"), "Package card");
  assert.equal(eventLabel("session_nudge"), "Next session reminder");
  assert.equal(eventLabel("grace_notice"), "Grace period notice");
});
test("other events keep the underscore-to-space label", () => {
  assert.equal(eventLabel("extended", null), "extended");
  assert.equal(eventLabel("session_consumed"), "session consumed");
});
test("delivery tick: sent, delivered, read; blocked and failed never read as sent; no status = not a message", () => {
  assert.equal(deliveryLabel("requested"), "✓ Sent");
  assert.equal(deliveryLabel("delivered"), "✓✓ Delivered");
  assert.equal(deliveryLabel("read"), "✓✓ Read");
  assert.doesNotMatch(deliveryLabel("blocked"), /✓|Sent/);
  assert.doesNotMatch(deliveryLabel("failed"), /✓/);
  assert.equal(deliveryLabel(null), null);
  assert.equal(deliveryLabel(undefined), null);
});
