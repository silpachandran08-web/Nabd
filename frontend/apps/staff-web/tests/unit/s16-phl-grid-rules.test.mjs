// PHL-007 (supplier-bill grid rules) and PHL-018 (checkout Rx block rules). `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const g = await import("../../app/lib/goodsReceipt.ts");
const r = await import("../../app/components/checkout/rxPrefill.ts");

const row = (o = {}) => ({ ...g.emptyRow(), itemId: "i1", batchNo: "B1", expiry: "04/28", mrp: "60", rate: "50", qty: "10", freeQty: "2", ...o });

test("expiry MM/YY becomes the last day of the month", () => {
  assert.equal(g.parseExpiry("04/28"), "2028-04-30");
  assert.equal(g.parseExpiry("02/2028"), "2028-02-29");
  assert.equal(g.parseExpiry("1327"), null);
  assert.equal(g.parseExpiry("abc"), null);
});

test("effective rate spreads free units over the paid price", () => {
  assert.equal(g.effectiveRate(50, 10, 2), 41.67);
  assert.equal(g.effectiveRate(50, 10, 0), 50);
});

test("a blank trailing row is ignored; a bad row names its problem", () => {
  assert.equal(g.isBlank(g.emptyRow()), true);
  assert.equal(g.rowProblem(row(), "2026-10-27"), null);
  assert.match(g.rowProblem(row({ expiry: "09/26" }), "2026-10-27"), /expired/);
  assert.match(g.rowProblem(row({ expiry: "x" }), "2026-10-27"), /MM\/YY/);
  assert.match(g.rowProblem(row({ qty: "0" }), "2026-10-27"), /Quantity/);
  assert.match(g.rowProblem(row({ itemId: "" }), "2026-10-27"), /item/);
});

test("body skips blank rows and totals the bill", () => {
  const rows = [row(), g.emptyRow()];
  const b = g.toBody(" Acme ", "B-1", "2026-10-27", rows);
  assert.equal(b.lines.length, 1);
  assert.equal(b.lines[0].expiryDate, "2028-04-30");
  assert.equal(b.supplierName, "Acme");
  assert.equal(g.billTotal(rows), 500);
});

test("checkout Rx block: short lines start unticked and need a reason", () => {
  const ok = { chargeCode: "A", chargeName: "A", qty: 2, prescriptionItemId: "p1", onHand: 5 };
  const short = { ...ok, chargeCode: "B", onHand: 1 };
  assert.deepEqual(r.initialTicks([ok, short]), ["A"]);
  assert.equal(r.isShort(short), true);
  assert.equal(r.reasonOk("ab"), false);
  assert.equal(r.reasonOk(" miscount "), true);
  const bl = r.toBillLine(short, { baseAmount: 10, taxRatePercent: 5, category: "Pharmacy" }, " count off ");
  assert.equal(bl.shortfallReason, "count off");
  assert.equal(r.toBillLine(ok, { baseAmount: 10, taxRatePercent: 5, category: "Pharmacy" }).shortfallReason, undefined);
});
