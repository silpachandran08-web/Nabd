// S9 QA (COM-005, independent): event label mapping and a source sweep for the removed "Send reminder" claim.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { eventLabel } from "../../app/packages/eventLabel.ts";

test("expiry_warning_sent maps to 'Marked as reminded (N-day)' for 30/14/7 and legacy notes", () => {
  assert.equal(eventLabel("expiry_warning_sent", "30-day: recorded only, no message sent"), "Marked as reminded (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "30-day template"), "Marked as reminded (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "14-day"), "Marked as reminded (14-day)");
  assert.equal(eventLabel("expiry_warning_sent", "7-day: x"), "Marked as reminded (7-day)");
});
test("no tier / null / empty / junk note falls back to 'Marked as reminded'", () => {
  for (const n of [null, undefined, "", "reminder", "day-30", " 30-day"]) assert.equal(eventLabel("expiry_warning_sent", n), "Marked as reminded");
});
test("label never contains 'sent'; other event types are only humanised", () => {
  for (const n of [null, "30-day template", "7-day: recorded only, no message sent"]) assert.ok(!/\bsent\b/i.test(eventLabel("expiry_warning_sent", n)));
  assert.equal(eventLabel("session_redeemed", null), "session redeemed");
  assert.equal(eventLabel("extended", "x"), "extended");
});
test("no 'Send reminder' / 'Reminder sent' string literal anywhere under frontend/app", () => {
  const hits = [];
  const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else if (/\.(tsx?|css)$/.test(f) && /send reminder|reminder sent|reminder was sent/i.test(readFileSync(p, "utf8"))) hits.push(p); } };
  walk(new URL("../../app", import.meta.url).pathname);
  assert.deepEqual(hits, []);
});
