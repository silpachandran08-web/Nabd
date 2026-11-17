// CLN-024: the scan card's per-line refills and validity, and the pad's change detection for validity / refills. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { lineTerms } from "../../app/components/setup/rxScan.ts";
import { rxFingerprint } from "../../app/components/consult/rx/rxLogic.ts";

const day = (iso) => iso.slice(0, 10);

test("a scanned line shows its refills used of allowed and its own validity date, and nothing when it has neither", () => {
  assert.equal(lineTerms({ refillsAllowed: 2, refillsUsed: 1, validUntil: "2026-03-31T10:00:00Z" }, day), " · refills 1 of 2 used · valid until 2026-03-31");
  assert.equal(lineTerms({ refillsAllowed: 0, refillsUsed: 0, validUntil: "2026-03-06T10:00:00Z" }, day), " · valid until 2026-03-06");
  assert.equal(lineTerms({ refillsAllowed: 0, refillsUsed: 0, validUntil: null }, day), "");
  assert.equal(lineTerms({}, day), "");
});

test("changing a line's validity days or refills is an unsaved change; the server's read-only validity is not", () => {
  const line = { drugName: "Metformin 500 mg", dosage: "", frequency: "", duration: "", instructions: "" };
  const base = rxFingerprint([line]);
  assert.notEqual(rxFingerprint([{ ...line, validityDays: 20 }]), base);
  assert.notEqual(rxFingerprint([{ ...line, refills: 2 }]), base);
  assert.equal(rxFingerprint([{ ...line, refills: 0, validity: { days: 30, maxDays: 30, refills: 0, maxRefills: 2, fixed: false, classId: "x", validUntil: null } }]), base);
});
