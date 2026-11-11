// COM-043: the desk's confirmation chip. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { confirmationChip } from "../../app/(clinic)/schedule/confirmationChip.ts";

test("a patient's confirm reads 'Confirmed by patient'; nudged and flagged warn; none and arrived show nothing", () => {
  assert.deepEqual(confirmationChip("confirmed", "scheduled"), { label: "Confirmed by patient", tone: "ok" });
  assert.equal(confirmationChip("nudged", "scheduled").tone, "warn");
  assert.equal(confirmationChip("unconfirmed_flagged", "scheduled").label, "Unconfirmed");
  assert.equal(confirmationChip("none", "scheduled"), null);
  assert.equal(confirmationChip("confirmed", "waiting"), null);
  assert.equal(confirmationChip(undefined, "scheduled"), null);
});
