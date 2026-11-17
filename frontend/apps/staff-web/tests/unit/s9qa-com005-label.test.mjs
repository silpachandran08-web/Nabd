// S9 QA (COM-005), updated for COM-044: the reminder is now a real send. The ledger label names the tier and never says "sent"
// (the delivery tick says what happened); "Send reminder" lives only on the packages screen.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { eventLabel } from "../../app/(clinic)/packages/eventLabel.ts";

test("expiry_warning_sent maps to 'Expiry reminder (N-day)' for 30/14/7 and legacy notes", () => {
  assert.equal(eventLabel("expiry_warning_sent", "30-day: recorded only, no message sent"), "Expiry reminder (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "30-day template"), "Expiry reminder (30-day)");
  assert.equal(eventLabel("expiry_warning_sent", "14-day"), "Expiry reminder (14-day)");
  assert.equal(eventLabel("expiry_warning_sent", "7-day: x"), "Expiry reminder (7-day)");
});
test("no tier / null / empty / junk note falls back to 'Expiry reminder'", () => {
  for (const n of [null, undefined, "", "reminder", "day-30", " 30-day"]) assert.equal(eventLabel("expiry_warning_sent", n), "Expiry reminder");
});
test("label never contains 'sent'; other event types are only humanised", () => {
  for (const n of [null, "30-day template", "7-day: recorded only, no message sent"]) assert.ok(!/\bsent\b/i.test(eventLabel("expiry_warning_sent", n)));
  assert.equal(eventLabel("session_redeemed", null), "session redeemed");
  assert.equal(eventLabel("extended", "x"), "extended");
});
test("'Send reminder' appears only on the packages screen", () => {
  const hits = [];
  const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else if (/\.(tsx?|css)$/.test(f) && /send reminder/i.test(readFileSync(p, "utf8"))) hits.push(p); } };
  walk(new URL("../../app", import.meta.url).pathname);
  assert.ok(hits.length > 0);
  assert.ok(hits.every((p) => p.includes("/(clinic)/packages/")), hits.join(", "));
});
