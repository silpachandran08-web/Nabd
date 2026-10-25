// FEF-013: the one money formatter (DESIGN.md §10), examples plus a rounding property.
import { expect, test } from "vitest";
import fc from "fast-check";
import { formatMoney } from "../src/money.ts";

test("missing or NaN amounts show a dash", () => {
  expect(formatMoney(null)).toBe("—");
  expect(formatMoney(undefined)).toBe("—");
  expect(formatMoney(Number.NaN)).toBe("—");
});

test("INR: en-IN lakh grouping, always two decimals (FEF-037)", () => {
  expect(formatMoney(123456)).toBe("₹1,23,456.00");
  expect(formatMoney(1234.5, "INR")).toBe("₹1,234.50");
  expect(formatMoney(0)).toBe("₹0.00");
});

test("SAR: ISO code by default, the riyal sign only when the clinic setting is on", () => {
  expect(formatMoney(1234, "SAR")).toBe("SAR 1,234.00");
  expect(formatMoney(0.005, "SAR", { sarSymbol: true })).toBe("0.01 ﷼");
});

test("other currencies show their ISO code with their own minor digits; an invalid code falls back to the plain code", () => {
  expect(formatMoney(10, "USD")).toBe("USD 10.00");
  expect(formatMoney("1234.567", "KWD")).toBe("KWD 1,234.567");
  expect(formatMoney(5, "NOT-A-CODE")).toBe("NOT-A-CODE 5");
});

test("property: never throws, and the shown amount is the input rounded to 2 decimals", () => {
  fc.assert(fc.property(fc.double({ noNaN: true, noDefaultInfinity: true, min: -1e9, max: 1e9 }), fc.constantFrom("INR", "SAR", "USD"), (n, cur) => {
    const s = formatMoney(n, cur);
    const shown = Number(s.replace(/[^\d.-]/g, ""));
    expect(Math.abs(shown - n)).toBeLessThanOrEqual(0.005 + 1e-6 * Math.abs(n));
    if (cur === "SAR") expect(s).toMatch(/^-?SAR /);
  }), { numRuns: 1000 });
});
