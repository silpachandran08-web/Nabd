// FEF-013: example tests plus fast-check properties for the sig parser and the quantity suggestion (DOC-033).
import { describe, expect, test } from "vitest";
import fc from "fast-check";
import { durationDays, formatQty, formatSig, parseSig, SIG_STEPS, suggestQuantity, unitsPerDay } from "../src/rxPadLogic.ts";

const RUNS = { numRuns: 1000 };
const step = fc.constantFrom(...SIG_STEPS);
const validSig = fc.tuple(step, step, step).map((t) => t.join("-"));
// Mix of junk, near-misses and valid patterns, so the parser sees all three.
const anySig = fc.oneof(fc.string(), validSig, fc.array(fc.oneof(step, fc.string({ maxLength: 3 })), { maxLength: 5 }).map((a) => a.join("-")), fc.constant(null), fc.constant(undefined));
const unit = fc.constantFrom("d", "day", "days", "w", "wk", "wks", "week", "weeks", "D", "Weeks", "month", "");
const anyDuration = fc.oneof(fc.string(), fc.tuple(fc.integer({ min: -5, max: 2000 }), fc.constantFrom("", " ", "  "), unit).map(([n, sp, u]) => `${n}${sp}${u}`), fc.constant(null), fc.constant(undefined));

describe("examples", () => {
  test("parseSig accepts the picker's steps only", () => {
    expect(parseSig("1-0-1")).toEqual(["1", "0", "1"]);
    expect(parseSig(" ½-1½-4 ")).toEqual(["½", "1½", "4"]);
    for (const bad of [null, undefined, "", "1-0", "1-0-1-1", "5-0-0", "1/0/1", "a-b-c"]) expect(parseSig(bad)).toBeNull();
    expect(formatSig(["1", "0", "1"])).toBe("1-0-1");
  });
  test("unitsPerDay sums the slots; an all-zero pattern is unknown", () => {
    expect(unitsPerDay("1-0-1")).toBe(2);
    expect(unitsPerDay("½-½-1½")).toBe(2.5);
    expect(unitsPerDay("0-0-0")).toBeNull();
    expect(unitsPerDay("junk")).toBeNull();
  });
  test("durationDays reads days and weeks, never months or bare numbers", () => {
    expect(durationDays("5 days")).toBe(5);
    expect(durationDays("2wk")).toBe(14);
    expect(durationDays("1 Week")).toBe(7);
    expect(durationDays(" 7d ")).toBe(7);
    for (const bad of [null, undefined, "", "5", "1 month", "0 days", "1000 days", "-3 days", "2.5 days"]) expect(durationDays(bad)).toBeNull();
  });
  test("suggestQuantity is sig x duration, null when either side is unknown", () => {
    expect(suggestQuantity("1-0-1", "5 days")).toEqual({ quantity: 10, perDay: 2, days: 5 });
    expect(suggestQuantity("½-0-½", "1 week")).toEqual({ quantity: 7, perDay: 1, days: 7 });
    expect(suggestQuantity("1-0-1", "1 month")).toBeNull();
    expect(suggestQuantity(null, "5 days")).toBeNull();
    expect(suggestQuantity("0-0-0", "5 days")).toBeNull();
    expect(suggestQuantity("4-4-4", "999 weeks")).toEqual({ quantity: 83916, perDay: 12, days: 6993 });
  });
  test("formatQty drops trailing zeros and rounds to 2 places", () => {
    expect(formatQty(10)).toBe("10");
    expect(formatQty(0.5)).toBe("0.5");
    expect(formatQty(1 / 3)).toBe("0.33");
  });
});

describe("properties (fast-check, 1,000 cases each)", () => {
  test("parseSig never throws; a parsed pattern round-trips through formatSig", () => {
    fc.assert(fc.property(anySig, (s) => {
      const slots = parseSig(s);
      if (slots) expect(parseSig(formatSig(slots))).toEqual(slots);
    }), RUNS);
  });
  test("suggestQuantity never throws and the quantity is never negative, zero or NaN", () => {
    fc.assert(fc.property(anySig, anyDuration, (s, d) => {
      const r = suggestQuantity(s, d);
      if (r === null) return;
      expect(Number.isFinite(r.quantity)).toBe(true);
      expect(r.quantity).toBeGreaterThan(0);
      expect(r.quantity).toBeLessThanOrEqual(999999);
      expect(r.perDay).toBeGreaterThan(0);
      expect(r.days).toBeGreaterThan(0);
    }), RUNS);
  });
  test("every valid non-zero sig with a valid duration gets exactly perDay x days", () => {
    fc.assert(fc.property(validSig, fc.integer({ min: 1, max: 999 }), fc.boolean(), (s, n, weeks) => {
      const r = suggestQuantity(s, `${n} ${weeks ? "weeks" : "days"}`);
      const perDay = unitsPerDay(s);
      if (perDay === null) { expect(r).toBeNull(); return; }
      const days = weeks ? n * 7 : n;
      expect(r).toEqual({ quantity: Math.round(perDay * days * 100) / 100, perDay, days });
    }), RUNS);
  });
  test("formatQty of any finite number is a parseable number string", () => {
    fc.assert(fc.property(fc.double({ noNaN: true, noDefaultInfinity: true, min: -1e9, max: 1e9 }), (n) => {
      expect(Number.isNaN(Number(formatQty(n)))).toBe(false);
    }), RUNS);
  });
});
