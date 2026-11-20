// PHL-009 quarantine bin: quantity/supplier/witness rules and the screen's wiring (gates, states, absent controls). `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const q = await import("../../app/lib/quarantine.ts");
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const batch = (o) => ({ batchId: "b1", itemId: "i1", itemName: "Syrup", batchNo: "S-1", qtyOnHand: 10, purchaseRate: 7.5, value: 75, supplierId: null, controlled: false, ...o });

test("reasons and methods are the ones the API accepts", () => {
  assert.deepEqual(q.QUARANTINE_REASONS.map((r) => r.id), ["expired", "damaged", "other"]);
  assert.deepEqual(q.WRITE_OFF_METHODS.map((m) => m.id), ["destroyed", "damaged", "lost"]);
});

test("quantity: blank = the whole batch, otherwise 1..on hand", () => {
  assert.equal(q.parseQty("", 10), 10);
  assert.equal(q.parseQty(undefined, 10), 10);
  assert.equal(q.parseQty(" 4 ", 10), 4);
  for (const bad of ["0", "11", "-1", "2.5", "x"]) assert.equal(q.parseQty(bad, 10), null, bad);
});

test("lines for the ticked batches, with the first fixable problem", () => {
  const a = batch({}), b = batch({ batchId: "b2", batchNo: "S-2", qtyOnHand: 20, purchaseRate: 2.25 });
  assert.equal(q.moveLines([], {}).problem, "Tick at least one batch.");
  assert.deepEqual(q.moveLines([a, b], { b1: "4" }), { lines: [{ batchId: "b1", qty: 4 }, { batchId: "b2", qty: 20 }], problem: null });
  assert.match(q.moveLines([a, b], { b2: "21" }).problem, /1 to 20 for Syrup \(S-2\)/);
  assert.equal(q.linesValue([a, b], [{ batchId: "b1", qty: 4 }, { batchId: "b2", qty: 20 }]), 75);
  assert.equal(q.linesValue([batch({ purchaseRate: null })], [{ batchId: "b1", qty: 3 }]), 0);
});

test("one return note per supplier; a controlled batch needs the witness", () => {
  const s1 = batch({ supplierId: "s1" }), s2 = batch({ batchId: "b2", supplierId: "s2" }), none = batch({ batchId: "b3" });
  assert.equal(q.supplierProblem([s1, none]), null);
  assert.match(q.supplierProblem([s1, s2]), /one return note per supplier/);
  assert.equal(q.supplierIdOf([none, s1]), "s1");
  assert.equal(q.supplierIdOf([none]), undefined);
  assert.equal(q.needsWitness([none]), false);
  assert.equal(q.needsWitness([none, batch({ controlled: true })]), true);
});

test("screen: on the pharmacy:view page, controls absent without the permission, loading/empty/error states, api client only", () => {
  const page = src("(clinic)/pharmacy/insights/page.tsx");
  assert.match(page, /<AccessGate permission="pharmacy:view"/);
  assert.match(page, /<QuarantineBin \/>/);
  const ui = src("components/setup/QuarantineBin.tsx");
  assert.match(ui, /const canEdit = can\("pharmacy:edit"\)/);
  assert.match(ui, /const canWriteOff = canEdit && can\("pharmacy:adjust_approve"\)/, "the witness picker needs pharmacy:edit too");
  assert.match(ui, /\{canEdit && \(/);
  assert.match(ui, /\{canWriteOff && items\.length > 0 && <button/);
  assert.doesNotMatch(ui, /disabled=\{!can/, "absent, not disabled");
  for (const st of ["ErrorState", "Skeleton", "EmptyState"]) assert.match(ui, new RegExp(`<${st}`), st);
  for (const path of ["/pharmacy/quarantine", "/pharmacy/reports/valuation", "/pharmacy/supplier-returns", "/pharmacy/write-offs"]) assert.ok(ui.includes(path), path);
  assert.match(ui, /needsWitness\(chosen\) && <ApproverStep/);
  assert.match(ui, /<PrintSheet/);
  assert.doesNotMatch(ui, /\bfetch\(/);
});
