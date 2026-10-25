// S12 QA-6 (independent) FEF-013: adversarial fast-check properties for the sig parser and quantity suggestion,
// boundaries for age()/initials()/formatMoney, and the consult-flow migration. 1,000 generated cases per property.
import { describe, expect, test } from "vitest";
import fc from "fast-check";
import { durationDays, parseSig, suggestQuantity, unitsPerDay, formatQty, SIG_STEPS } from "../src/rxPadLogic.ts";
import { age, initials, minutesSince } from "../src/format.ts";
import { formatMoney } from "../src/money.ts";
import { VISIT_LABEL, VISIT_SOURCES } from "../src/status.ts";

const RUNS = { numRuns: 1000 };
// Characters the parsers care about, plus look-alikes (fullwidth digits, minus sign, en dash, NBSP, Arabic-Indic digits).
const SIG_CHARS = ["0", "1", "2", "3", "4", "5", "½", "1½", "-", "−", "–", " ", " ", "/", ".", "e", "١", "１", "\t"];
const DUR_CHARS = ["0", "1", "9", " ", " ", "d", "D", "w", "W", "k", "s", "a", "y", "e", "e", "-", "+", ".", "x", "١", "ays", "eek"];
const junkSig = fc.array(fc.constantFrom(...SIG_CHARS), { maxLength: 12 }).map((a) => a.join(""));
const junkDur = fc.array(fc.constantFrom(...DUR_CHARS), { maxLength: 14 }).map((a) => a.join(""));
const nearSig = fc.tuple(fc.constantFrom(...SIG_STEPS, "5", "-1", "1.5", "0.5", "½½", ""), fc.constantFrom(...SIG_STEPS, "NaN", "Infinity"), fc.constantFrom(...SIG_STEPS, "1e3"))
  .map((t) => t.join("-"));
const nearDur = fc.tuple(fc.oneof(fc.integer({ min: -1000, max: 100000 }).map(String), fc.constantFrom("1e2", "0x10", "Infinity", "NaN", "007", "1.0", "")),
  fc.constantFrom("", " ", "  "), fc.constantFrom("d", "day", "days", "w", "wk", "wks", "week", "weeks", "WEEKS", "months", "dd", "weekss"))
  .map(([n, s, u]) => `${n}${s}${u}`);
const sig = fc.oneof(fc.string(), fc.string({ unit: "grapheme" }), junkSig, nearSig, fc.constant(null), fc.constant(undefined));
const dur = fc.oneof(fc.string(), fc.string({ unit: "grapheme" }), junkDur, nearDur, fc.constant(null), fc.constant(undefined));

const okNumber = (n: unknown) => typeof n === "number" && Number.isFinite(n) && n > 0;

describe("FEF-013 AC2 sig parser + quantity suggestion (QA-6, fast-check 1,000 cases each)", () => {
  test("suggestQuantity never throws; quantity is null or finite, > 0 and never NaN (adversarial input)", () => {
    fc.assert(fc.property(sig, dur, (s, d) => {
      const r = suggestQuantity(s, d);
      if (r === null) return true;
      return okNumber(r.quantity) && okNumber(r.perDay) && okNumber(r.days) && r.quantity <= 999999;
    }), RUNS);
  });
  test("parseSig / unitsPerDay / durationDays never throw; numbers they return are finite and positive", () => {
    fc.assert(fc.property(sig, dur, (s, d) => {
      const slots = parseSig(s);
      if (slots !== null && !(slots.length === 3 && slots.every((x) => (SIG_STEPS as readonly string[]).includes(x)))) return false;
      const u = unitsPerDay(s);
      const days = durationDays(d);
      return (u === null || okNumber(u)) && (days === null || (Number.isInteger(days) && days > 0));
    }), RUNS);
  });
  test("quantity equals perDay x days rounded to 2 places for every accepted pair (no silent re-scaling)", () => {
    fc.assert(fc.property(sig, dur, (s, d) => {
      const r = suggestQuantity(s, d);
      return r === null || r.quantity === Math.round(r.perDay * r.days * 100) / 100;
    }), RUNS);
  });
  test("formatQty of a suggested quantity never prints NaN, Infinity or a minus sign", () => {
    fc.assert(fc.property(sig, dur, (s, d) => {
      const r = suggestQuantity(s, d);
      return r === null || /^\d+(\.\d{1,2})?$/.test(formatQty(r.quantity));
    }), RUNS);
  });
  test("boundaries: zero/negative/huge/look-alike input gives null, never 0 or a negative number", () => {
    expect(suggestQuantity("0-0-0", "5 days")).toBeNull();
    expect(suggestQuantity("1-0-1", "0 days")).toBeNull();
    expect(suggestQuantity("1-0-1", "-2 days")).toBeNull();
    expect(suggestQuantity("-1-0-1", "2 days")).toBeNull();
    expect(suggestQuantity("1−0−1", "2 days")).toBeNull(); // U+2212 minus is not the picker's hyphen
    expect(suggestQuantity("1-0-1", "１ day")).toBeNull(); // fullwidth digit
    expect(suggestQuantity("1-0-1", "1e2 days")).toBeNull();
    expect(suggestQuantity("1-0-1", "1000 days")).toBeNull();
    expect(suggestQuantity("1-0-1", "999 days")).toEqual({ quantity: 1998, perDay: 2, days: 999 });
    expect(suggestQuantity("½-0-0", "1 day")).toEqual({ quantity: 0.5, perDay: 0.5, days: 1 });
    expect(suggestQuantity("4-4-4", "999 weeks")?.quantity).toBe(83916); // the largest pair the picker allows
    expect(suggestQuantity(" 1-1-1 ", " 3 d ")).toEqual({ quantity: 9, perDay: 3, days: 3 });
  });
});

describe("FEF-013 AC4 helpers: boundaries (QA-6)", () => {
  test("initials: blank, extra spaces, three names, lower case, Arabic", () => {
    expect(initials("")).toBe("");
    expect(initials("   ")).toBe("");
    expect(initials("  asha   rao  ")).toBe("AR");
    expect(initials("asha rao kumar")).toBe("AR");
    expect(initials("ليلى حداد")).toBe("لح");
  });
  test("initials property: never throws, at most 2 characters (code units of the first letters)", () => {
    fc.assert(fc.property(fc.string(), (s) => {
      const r = initials(s);
      return typeof r === "string" && [...r].length <= 2;
    }), RUNS);
  });
  // Local-midnight dates: the age on the birthday and the day before (the clinic day, in the browser's zone).
  test("age: the day before the birthday is one less, the birthday itself counts (in this process's TZ)", () => {
    expect(age("2000-06-15", new Date(2026, 5, 14))).toBe(25);
    expect(age("2000-06-15", new Date(2026, 5, 15))).toBe(26);
    expect(age("2000-06-15", new Date(2026, 5, 15, 23, 59))).toBe(26);
  });
  test("age: leap-day birthday is 25 on 28 Feb 2026 and 26 on 1 Mar 2026", () => {
    expect(age("2000-02-29", new Date(2026, 1, 28))).toBe(25);
    expect(age("2000-02-29", new Date(2026, 2, 1))).toBe(26);
  });
  test("age: newborn today is 0", () => {
    expect(age("2026-10-20", new Date(2026, 9, 20, 12))).toBe(0);
  });
  test("minutesSince: never negative, null for junk", () => {
    expect(minutesSince("2026-10-20T10:00:00Z", Date.parse("2026-10-20T09:00:00Z"))).toBe(0);
    expect(minutesSince("not a date", Date.now())).toBeNull();
    expect(minutesSince(null, Date.now())).toBeNull();
  });
  test("VISIT_LABEL covers every VisitSource", () => {
    for (const s of VISIT_SOURCES) expect(VISIT_LABEL[s]).toBeTruthy();
  });
});

describe("FEF-013 money formatter (QA-6)", () => {
  test("formatMoney never throws and never prints NaN for any finite amount or currency code", () => {
    fc.assert(fc.property(fc.double({ noNaN: true, noDefaultInfinity: true, min: -1e12, max: 1e12 }), fc.constantFrom("INR", "SAR", "USD", "XXX", "bad", ""), (n, c) => {
      const s = formatMoney(n, c);
      return typeof s === "string" && !s.includes("NaN");
    }), RUNS);
  });
  test("formatMoney boundaries: null/undefined/NaN show a dash; 2 decimals; SAR ISO code", () => {
    expect(formatMoney(null)).toBe("—");
    expect(formatMoney(undefined)).toBe("—");
    expect(formatMoney(NaN)).toBe("—");
    expect(formatMoney(100000, "INR")).toBe("₹1,00,000.00"); // FEF-037: always the currency's minor digits
    expect(formatMoney(0.005, "INR")).toMatch(/^₹0\.01$|^₹0\.00$/);
    expect(formatMoney(12.5, "SAR")).toBe("SAR 12.50"); // FEF-037: ISO code; the riyal sign is a clinic setting (sarSymbol)
  });
});
