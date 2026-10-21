// FEF-013: DOC-036 / CLN-003 dose calculator helpers (pure; the component checks stay in staff-web tests/unit).
import { expect, test } from "vitest";
import fc from "fast-check";
import { rateError, STALE_WEIGHT_MONTHS, weightAge, weightError } from "../src/doseLogic.ts";

test("unknown or garbage recordedAt is never fresh", () => {
  for (const v of [null, undefined, "", "not-a-date"]) expect(weightAge(v).state).toBe("unknown");
});

test("3-month boundary: exactly 3 calendar months is fresh, one minute later stale", () => {
  const rec = "2026-01-15T10:00:00Z";
  expect(STALE_WEIGHT_MONTHS).toBe(3);
  expect(weightAge(rec, new Date("2026-04-15T10:00:00Z"))).toMatchObject({ state: "fresh", months: 3 });
  expect(weightAge(rec, new Date("2026-04-15T10:01:00Z")).state).toBe("stale");
});

test("months count whole calendar months and never go negative", () => {
  expect(weightAge("2026-01-31T00:00:00Z", new Date("2026-02-15T00:00:00Z"))).toMatchObject({ state: "fresh", months: 0 });
  expect(weightAge("2026-05-01T00:00:00Z", new Date("2026-04-01T00:00:00Z"))).toMatchObject({ months: 0 });
  expect(weightAge("2025-01-10T00:00:00Z", new Date("2026-01-20T00:00:00Z"))).toMatchObject({ state: "stale", months: 12 });
});

test("weight must be 0.2 to 400 kg (the vitals API bounds)", () => {
  expect(weightError("")).toBeTruthy();
  expect(weightError("  ")).toBeTruthy();
  expect(weightError("abc")).toBeTruthy();
  expect(weightError("0.19")).toBeTruthy();
  expect(weightError("400.1")).toBeTruthy();
  expect(weightError("0.2")).toBeNull();
  expect(weightError("400")).toBeNull();
});

test("mg/kg rate must be a positive number", () => {
  for (const bad of ["", "0", "-1", "abc", "Infinity"]) expect(rateError(bad)).toBeTruthy();
  expect(rateError("12.5")).toBeNull();
});

test("property: validators never throw and accept exactly the finite numbers in range", () => {
  fc.assert(fc.property(fc.oneof(fc.string(), fc.double().map(String)), (raw) => {
    const n = Number(raw.trim());
    const okWeight = raw.trim() !== "" && Number.isFinite(n) && n >= 0.2 && n <= 400;
    expect(weightError(raw) === null).toBe(okWeight);
    const okRate = raw.trim() !== "" && Number.isFinite(n) && n > 0;
    expect(rateError(raw) === null).toBe(okRate);
  }), { numRuns: 1000 });
});
