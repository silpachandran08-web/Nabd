// PHL-011 counter return overlay rules. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const r = await import("../../app/components/checkout/pharmacyReturn.ts");

const line = (o = {}) => ({ invoiceLineId: "l1", chargeCode: "PHM-1", name: "Cetirizine", soldQty: 2, returnedQty: 0, returnableQty: 2, unitPrice: 20,
  taxRatePercent: 12, allowed: true, ...o });
const bill = (lines) => ({ invoiceId: "inv1", invoiceNumber: "INV-1", status: "paid", currency: "INR", refundMethods: ["cash"], lines });

test("a refused line shows the server's reason; an opened pack is refused too", () => {
  assert.equal(r.lineBlock(line({ allowed: false, reason: "Schedule H1 item: controlled medicines can't be taken back." }), false),
    "Schedule H1 item: controlled medicines can't be taken back.");
  assert.equal(r.lineBlock(line(), true), r.OPENED_REASON);
  assert.equal(r.lineBlock(line(), false), null);
});

test("quantity is a whole number from 1 to what is still returnable", () => {
  assert.equal(r.parseQty("1", 2), 1);
  assert.equal(r.parseQty(" 2 ", 2), 2);
  assert.equal(r.parseQty("3", 2), null);
  assert.equal(r.parseQty("0", 2), null);
  assert.equal(r.parseQty("1.5", 2), null);
});

test("the body carries only ticked, sealed lines and the optional refund method", () => {
  const b = bill([line(), line({ invoiceLineId: "l2", name: "ORS" })]);
  assert.deepEqual(r.returnBody(b, { l1: { qty: "1", opened: false } }), { body: { invoiceId: "inv1", lines: [{ invoiceLineId: "l1", qty: 1, condition: "sealed" }] } });
  assert.deepEqual(r.returnBody(b, { l2: { qty: "2", opened: false } }, "cash").body.refundMethod, "cash");
  assert.match(r.returnBody(b, {}).problem, /Tick at least one/);
  assert.match(r.returnBody(b, { l1: { qty: "1", opened: true } }).problem, /Opened pack/);
  assert.match(r.returnBody(b, { l1: { qty: "5", opened: false } }).problem, /1 to 2/);
});

test("server problems become the overlay's messages", () => {
  assert.match(r.returnErrorMessage({ code: "APPROVAL_PENDING", detail: "x" }, "f"), /already waiting/);
  assert.equal(r.returnErrorMessage({ code: "RETURN_EXCEEDS_SOLD", detail: "Only 1 unit(s) of this line can still be returned." }, "f"),
    "Only 1 unit(s) of this line can still be returned.");
  assert.equal(r.returnErrorMessage(null, "fallback"), "fallback");
  assert.match(r.decisionErrorMessage({ code: "SAME_USER" }, "f"), /Someone else/);
});

test("only pending counter returns are listed for the approver", () => {
  const items = [{ id: "a", reasonCode: "customer_return", status: "pending" }, { id: "b", reasonCode: "billing_error", status: "pending" },
    { id: "c", reasonCode: "customer_return", status: "approved" }];
  assert.deepEqual(r.pendingReturns(items).map((x) => x.id), ["a"]);
});

test("the return action is absent without billing:edit and the approvals without billing:approve", () => {
  const page = readFileSync(new URL("../../app/(clinic)/checkout/otc/page.tsx", import.meta.url), "utf8");
  assert.match(page, /canReturn = can\("billing:edit"\)/);
  assert.match(page, /\{canReturn && <button/);
  const dlg = readFileSync(new URL("../../app/components/checkout/ReturnDialog.tsx", import.meta.url), "utf8");
  assert.match(dlg, /canDecide = can\("billing:approve"\) && can\("billing:view"\)/);
  assert.match(dlg, /\{canDecide && <PendingReturns/);
});
