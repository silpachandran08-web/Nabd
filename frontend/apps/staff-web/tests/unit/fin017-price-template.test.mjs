import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { amountError, bulkBody, gridError, move, preview, rowsOf, taxBadge } from "../../app/lib/priceTemplate.ts";

const tpl = {
  templateId: "t1", specialty: "derma", jurisdiction: "IN", version: 1, reviewed: false, reviewNote: "pending", currency: "INR",
  heads: [
    { code: "LASER", name: "Laser session", category: "Cosmetic", baseAmount: 4000, taxCategory: "taxable", taxRatePercent: 18, sacHsn: "999722", displayOrder: 20 },
    { code: "CONS", name: "Consultation", category: "Consultation", baseAmount: 700, taxCategory: "exempt", taxRatePercent: 0, sacHsn: "999312", displayOrder: 10 },
  ],
};

test("rows come in display order with the suggested amounts", () => {
  const rows = rowsOf(tpl);
  assert.deepEqual(rows.map((r) => r.code), ["CONS", "LASER"]);
  assert.equal(rows[0].amount, "700");
  assert.ok(rows.every((r) => r.include));
});

test("tax badges: GST in India, VAT in the Gulf", () => {
  assert.equal(taxBadge("taxable", 18, "IN"), "GST 18%");
  assert.equal(taxBadge("exempt", 0, "IN"), "Exempt");
  assert.equal(taxBadge("taxable", 15, "SA"), "VAT 15%");
  assert.equal(taxBadge("zero_rated", 0, "AE"), "VAT 0%");
  assert.equal(taxBadge(null, 0, "IN"), "");
});

test("preview reflects edited labels, order and badges at once, and keeps existing heads", () => {
  let rows = rowsOf(tpl);
  rows[1] = { ...rows[1], name: "Laser (face)", amount: "4500" };
  rows = move(rows, 1, -1);
  const existing = [{ code: "REG", name: "Registration", category: "Service", baseAmount: 100, taxRatePercent: 0, active: true, displayOrder: 1 }];
  const p = preview(rows, existing, "IN");
  assert.deepEqual(p.buttons.map((b) => b.name), ["Registration", "Laser (face)", "Consultation"]);
  assert.deepEqual(p.buttons.map((b) => b.badge), ["", "GST 18%", "Exempt"]);
  assert.equal(p.buttons[1].amount, 4500);
  assert.deepEqual(p.categories, ["All", "Service", "Cosmetic", "Consultation"]);
  rows[0] = { ...rows[0], include: false };
  assert.deepEqual(preview(rows, existing, "IN").buttons.map((b) => b.code), ["REG", "CONS"]);
});

test("a code the clinic already has shows its own head, not the template's", () => {
  const existing = [{ code: "cons", name: "Our consult", category: "Consultation", baseAmount: 999, taxRatePercent: 0, active: true, displayOrder: 5 }];
  const p = preview(rowsOf(tpl), existing, "IN");
  assert.deepEqual(p.buttons.map((b) => [b.name, b.isNew]), [["Our consult", false], ["Laser session", true]]);
  assert.equal(gridError(rowsOf(tpl).map((r) => ({ ...r, include: r.code === "CONS" })), existing), "Every included head is already in your price list.");
});

test("preview is computed locally well inside 200 ms for a large list", () => {
  const heads = Array.from({ length: 50 }, (_, i) => ({ ...tpl.heads[0], code: `C${i}`, displayOrder: i }));
  const rows = rowsOf({ ...tpl, heads });
  const t0 = performance.now();
  for (let n = 0; n < 20; n++) preview(rows, [], "IN");
  assert.ok((performance.now() - t0) / 20 < 200);
  const src = readFileSync(new URL("../../app/lib/priceTemplate.ts", import.meta.url), "utf8");
  assert.doesNotMatch(src, /\bfetch\b|\bapi\./); // no round-trip per edit
});

test("validation and the bulk body", () => {
  assert.equal(amountError("12.50"), null);
  assert.ok(amountError("-1")); assert.ok(amountError("1.234")); assert.ok(amountError(""));
  const rows = rowsOf(tpl);
  rows[0] = { ...rows[0], amount: "x" };
  assert.match(gridError(rows, []), /^CONS:/);
  rows[0] = { ...rows[0], amount: "650", name: " Skin consult " };
  rows[1] = { ...rows[1], include: false };
  assert.deepEqual(bulkBody(tpl, rows), { templateId: "t1", heads: [{ code: "CONS", name: "Skin consult", category: "Consultation", baseAmount: 650,
    taxCategory: "exempt", taxRatePercent: 0, sacHsn: "999312", displayOrder: 10 }] });
});
