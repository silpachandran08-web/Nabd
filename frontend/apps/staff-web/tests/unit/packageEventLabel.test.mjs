// COM-005: ledger label never claims a message was sent. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { eventLabel } from "../../app/(clinic)/packages/eventLabel.ts";

test("reminder event reads Marked as reminded with the tier", () => {
  assert.equal(eventLabel("expiry_warning_sent", "30-day: recorded only, no message sent"), "Marked as reminded (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "7-day template"), "Marked as reminded (7-day)"); // legacy note
});
test("no tier in note falls back without 'sent'", () => {
  for (const n of [null, undefined, "", "x"]) assert.equal(eventLabel("expiry_warning_sent", n), "Marked as reminded");
});
test("other events keep the underscore-to-space label", () => {
  assert.equal(eventLabel("extended", null), "extended");
  assert.equal(eventLabel("session_consumed"), "session consumed");
});
