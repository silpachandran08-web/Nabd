// REC-044: split-tender maths. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { toMinor, tenderTotalMinor, tenderError } from "../../app/components/checkout/tender.ts";

const row = (amount, method = "cash", id = 1) => ({ id, method, amount });

test("toMinor accepts positive amounts with at most 2 decimals only", () => {
  assert.equal(toMinor("10"), 1000);
  assert.equal(toMinor("0.30"), 30);
  assert.equal(toMinor("19.99"), 1999);
  for (const bad of ["", "0", "-5", "1.234", "abc", "1e3", "1,5"]) assert.equal(toMinor(bad), null, bad);
});

test("sum is exact in minor units (0.1 + 0.2 style drift cannot happen)", () => {
  assert.equal(tenderTotalMinor([row("0.10"), row("0.20", "card", 2)]), 30);
});

test("blank rows are ignored; over-balance and malformed rows are refused, never clamped", () => {
  assert.equal(tenderError([row("")], 500), null);
  assert.equal(tenderError([row("300"), row("200", "card", 2)], 50000), null);
  assert.match(tenderError([row("300"), row("201", "card", 2)], 50000), /more than/);
  assert.match(tenderError([row("12.345")], 50000), /positive number/);
});
