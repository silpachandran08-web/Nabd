// PHL-002 / PHL-004: pure rules for the class badge and the capability-driven nav.
import { test } from "node:test";
import assert from "node:assert/strict";
import { classBadge, registerBadge, pharmacyNavAllowed } from "../../app/lib/pharmacyRules.ts";

test("class badges: OTC, Rx, H1, X, NDPS, SCD, CD", () => {
  const want = { OTC: "OTC", IN_H: "Rx", IN_H1: "H1", IN_X: "X", IN_NDPS: "NDPS", SA_RX: "Rx", SA_SEMI_CONTROLLED: "SCD",
    SA_CONTROLLED: "CD", AE_RX: "Rx", AE_SCD: "SCD", AE_CD: "CD", QA_RX: "Rx", QA_CD: "CD" };
  for (const [code, badge] of Object.entries(want)) assert.equal(classBadge(code), badge, code);
  assert.equal(classBadge(null), "OTC");
  assert.equal(classBadge(undefined), "OTC");
});

test("register badge only when the server says a register applies", () => {
  assert.equal(registerBadge(null), null);
  assert.equal(registerBadge(undefined), null);
  assert.equal(registerBadge("IN_H1"), "Register · H1");
  assert.equal(registerBadge("AE_CD"), "Register · CD");
});

test("nav: External hides Pharmacy, Hybrid and In-house show it, unknown hides nothing, other items untouched", () => {
  assert.equal(pharmacyNavAllowed("/pharmacy", { mode: "external", features: [] }), false);
  assert.equal(pharmacyNavAllowed("/pharmacy", { mode: "hybrid", features: ["items"] }), true);
  assert.equal(pharmacyNavAllowed("/pharmacy", { mode: "in_house", features: ["items", "batches"] }), true);
  assert.equal(pharmacyNavAllowed("/pharmacy", null), true);
  assert.equal(pharmacyNavAllowed("/billing", { mode: "external", features: [] }), true);
  assert.equal(pharmacyNavAllowed("/pharmacy?x=1", { mode: "external", features: [] }), false);
});
