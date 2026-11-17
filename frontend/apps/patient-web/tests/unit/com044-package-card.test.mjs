// COM-044: the package card page (money formatting, every catalogue has every card string with the same placeholders).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { formatMoney, makeT } = await import("../../app/components/util.ts");

const cat = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/patient.json`, import.meta.url), "utf8"));

test("money is shown in the clinic's currency, a plain number without one", () => {
  assert.match(formatMoney(2400, "en-IN", "INR"), /₹\s?2,400/);
  assert.equal(formatMoney(600, "en", null), "600");
  assert.equal(formatMoney(12.5, "en", "NOT-A-CODE"), "12.50");
});

test("every language has every package card string with the same placeholders", () => {
  const en = cat("en");
  const keys = Object.keys(en).filter((k) => k.startsWith("pkg"));
  assert.ok(keys.length >= 16);
  const vars = (s) => (s.match(/\{\w+\}/g) ?? []).sort().join(",");
  for (const l of ["hi", "kn", "te"]) {
    const c = cat(l);
    for (const k of keys) {
      assert.ok(c[k], `${l} ${k}`);
      assert.equal(vars(c[k]), vars(en[k]), `${l} ${k}`);
    }
  }
  assert.equal(makeT(en)("pkgSessionsValue", { used: "1", total: "6", left: "5" }), "1 of 6 used, 5 left");
});
