// PHL-010 stock-take sheet rules. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const s = await import("../../app/lib/stockTake.ts");

const line = (o = {}) => ({ batchId: "b1", location: "Shelf A", expectedQty: 10, controlled: false, ...o });
const draft = (o = {}) => ({ counted: "", reasonCode: "", note: "", ...o });

test("reason codes are exactly the fixed list", () => {
  assert.deepEqual(s.REASONS.map((r) => r.code).sort(), ["count_correction", "damaged", "expired", "other", "theft_loss"]);
});

test("counts are whole numbers; blank means not counted", () => {
  assert.equal(s.parseCount(" 7 "), 7);
  assert.equal(s.parseCount("0"), 0);
  assert.equal(s.parseCount(""), null);
  assert.equal(s.parseCount("-1"), null);
  assert.equal(s.parseCount("2.5"), null);
});

test("variance is counted minus expected (snapshot plus movements)", () => {
  assert.equal(s.variance(line(), draft({ counted: "8" })), -2);
  assert.equal(s.variance(line(), draft({ counted: "10" })), 0);
  assert.equal(s.variance(line(), draft()), null);
  assert.equal(s.variance(line(), undefined), null);
});

test("the sheet groups by location in order and keeps unlocated batches together", () => {
  const g = s.byLocation([line({ batchId: "1" }), line({ batchId: "2", location: "Shelf B" }), line({ batchId: "3" }), line({ batchId: "4", location: null })]);
  assert.deepEqual(g.map((x) => [x.location, x.lines.map((l) => l.batchId)]), [["Shelf A", ["1", "3"]], ["Shelf B", ["2"]], ["No location", ["4"]]]);
});

test("approval is needed only for a controlled line with a variance", () => {
  const v = { a: -1, b: 0, c: 3 };
  const fn = (l) => v[l.batchId];
  assert.equal(s.needsApproval([line({ batchId: "a" }), line({ batchId: "c" })], fn), false);
  assert.equal(s.needsApproval([line({ batchId: "b", controlled: true })], fn), false);
  assert.equal(s.needsApproval([line({ batchId: "c", controlled: true })], fn), true);
});

test("the loss reminder shows for a reduction of a controlled drug only, and names no regulation", () => {
  assert.equal(s.lossReminder(true, -1), true);
  assert.equal(s.lossReminder(true, 2), false);
  assert.equal(s.lossReminder(false, -1), false);
  assert.equal(s.lossReminder(true, null), false);
  assert.doesNotMatch(s.LOSS_REMINDER, /NDPS|SFDA|Form|Act\b/);
});

test("other needs a note and a count must be a whole number", () => {
  assert.equal(s.draftProblem(draft({ counted: "3", reasonCode: "other" })), "Reason \"Other\" needs a note.");
  assert.equal(s.draftProblem(draft({ counted: "3", reasonCode: "other", note: "sample" })), null);
  assert.equal(s.draftProblem(draft({ counted: "x" })), "Enter the counted quantity as a whole number.");
});
