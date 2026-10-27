import { test } from "vitest";
import assert from "node:assert/strict";
import { ISSUING_BODIES, LICENCE_KINDS, daysLeft, remindersText } from "../src/licence.ts";

test("days left counts calendar days, negative once expired", () => {
  assert.equal(daysLeft("2026-03-10", "2026-03-10"), 0);
  assert.equal(daysLeft("2026-05-09", "2026-03-10"), 60);
  assert.equal(daysLeft("2026-03-09", "2026-03-10"), -1);
  assert.equal(daysLeft("2027-01-01", "2026-12-31"), 1);
});
test("reminder text lists the sent thresholds, largest first", () => {
  assert.equal(remindersText([7, 60]), "60d, 7d");
  assert.equal(remindersText([]), "");
  assert.equal(remindersText(null), "");
});
test("UAE offers DHA, DOH and MOHAP; every kind of the register is listed", () => {
  assert.deepEqual(ISSUING_BODIES.AE, ["DHA", "DOH", "MOHAP"]);
  assert.ok(["clinician", "facility", "nurse", "lab", "pharmacist"].every((k) => (LICENCE_KINDS as readonly string[]).includes(k)));
});
