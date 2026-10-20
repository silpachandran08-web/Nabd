// FEF-013: the one money formatter (DESIGN.md §10), examples plus a rounding property.
import { expect, test } from "vitest";
import fc from "fast-check";
import { formatMoney } from "../src/money.ts";

test("missing or NaN amounts show a dash", () => {
  expect(formatMoney(null)).toBe("—");
  expect(formatMoney(undefined)).toBe("—");
  expect(formatMoney(Number.NaN)).toBe("—");
});

test("INR: en-IN lakh grouping, no decimals for whole amounts, two otherwise", () => {
  expect(formatMoney(123456)).toBe("₹1,23,456");
  expect(formatMoney(1234.5, "INR")).toBe("₹1,234.50");
  expect(formatMoney(0)).toBe("₹0");
});

test("SAR: two decimals and a trailing riyal sign", () => {
  expect(formatMoney(1234, "SAR")).toBe("1,234.00 ﷼");
  expect(formatMoney(0.005, "SAR")).toBe("0.01 ﷼");
});

test("other currencies use en-US; an invalid code falls back to the plain code", () => {
  expect(formatMoney(10, "USD")).toBe("$10");
  expect(formatMoney(5, "NOT-A-CODE")).toBe("NOT-A-CODE 5");
});

test("property: never throws, and the shown amount is the input rounded to 2 decimals", () => {
  fc.assert(fc.property(fc.double({ noNaN: true, noDefaultInfinity: true, min: -1e9, max: 1e9 }), fc.constantFrom("INR", "SAR", "USD"), (n, cur) => {
    const s = formatMoney(n, cur);
    const shown = Number(s.replace(/[^\d.-]/g, ""));
    expect(Math.abs(shown - n)).toBeLessThanOrEqual(0.005 + 1e-6 * Math.abs(n));
    if (cur === "SAR") expect(s).toMatch(/\.\d{2} ﷼$/);
  }), { numRuns: 1000 });
});
