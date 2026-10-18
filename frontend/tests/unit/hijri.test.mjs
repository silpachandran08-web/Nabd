// REC-032: Hijri label next to date pickers. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { hijriLabel } from "../../app/lib/hijri.ts";

test("known Gregorian dates map to Umm al-Qura", () => {
  assert.equal(hijriLabel("2026-10-18"), "7 Jumada I 1448 AH");
  assert.equal(hijriLabel("2025-03-01"), "1 Ramadan 1446 AH");
});

test("month boundaries and leap day do not throw", () => {
  assert.match(hijriLabel("2024-02-29"), /^\d+ \S+.* 1445 AH$/);
  assert.match(hijriLabel("2026-12-31"), / AH$/);
});

test("empty or invalid input gives empty string", () => {
  for (const v of [null, undefined, "", "abc", "2026-13-40", "2026-02-30x"]) assert.equal(hijriLabel(v), "");
});
