// PLT-059: plan entitlements in staff-web: nav gating, kill banner, seat meter. Pure rules only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { killBanner, navAllowed, seatMeter } from "../../app/lib/entitlementRules.ts";

const ent = (enabled, killed = []) => ({
  modules: ["billing", "packages", "pharmacy_inhouse", "pharmacy_external", "lab"].map((code) => ({ code, enabled: enabled.includes(code) })),
  killedFeatures: killed, seatLimit: 5,
});

test("unknown entitlements hide nothing (the server still enforces writes)", () => {
  for (const h of ["/pharmacy", "/packages", "/billing", "/patients"]) assert.equal(navAllowed(h, null), true);
});

test("a module the plan lacks is absent from navigation; ungated items stay", () => {
  const e = ent(["billing"]);
  assert.equal(navAllowed("/billing", e), true);
  assert.equal(navAllowed("/packages", e), false);
  assert.equal(navAllowed("/pharmacy", e), false);
  assert.equal(navAllowed("/patients", e), true);
  assert.equal(navAllowed("/setup?tab=subscription", e), true);
});

test("pharmacy shows for in-house or external", () => {
  assert.equal(navAllowed("/pharmacy", ent(["pharmacy_external"])), true);
  assert.equal(navAllowed("/pharmacy", ent(["pharmacy_inhouse"])), true);
});

test("kill banner: absent when nothing is killed, names the feature otherwise", () => {
  assert.equal(killBanner(null), null);
  assert.equal(killBanner(ent([])), null);
  assert.match(killBanner(ent([], ["whatsapp_bot"])), /WhatsApp assistant is switched off/);
  assert.match(killBanner(ent([], ["whatsapp_bot", "lab"])), /are switched off/);
});

test("seat meter: used of limit, capped at 100, full at the limit, unlimited without a limit", () => {
  assert.deepEqual(seatMeter(3, 5), { label: "3 of 5 seats used", percent: 60, full: false });
  assert.equal(seatMeter(5, 5).full, true);
  assert.equal(seatMeter(7, 5).percent, 100);
  assert.deepEqual(seatMeter(2, null), { label: "2 seats used (no limit)", percent: null, full: false });
});
