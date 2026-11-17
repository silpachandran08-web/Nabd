// CLN-050: FDI <-> Universal is a display mapping only (storage stays FDI), supernumerary labels, preset codes, drawer gating.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { FDI_TEETH, fdiToUniversal, presetCode, toothLabel, universalToFdi } from "../../app/lib/dental.ts";

test("every permanent FDI tooth maps to one Universal number 1-32 and back", () => {
  const u = FDI_TEETH.map(fdiToUniversal);
  assert.deepEqual([...u].sort((a, b) => a - b), Array.from({ length: 32 }, (_, i) => i + 1));
  for (const n of FDI_TEETH) assert.equal(universalToFdi(fdiToUniversal(n)), n);
  // anchor points of the standard chart
  assert.deepEqual([18, 11, 21, 28, 38, 31, 41, 48, 36, 46].map(fdiToUniversal), [1, 8, 9, 16, 17, 24, 25, 32, 19, 30]);
});

test("non-FDI codes have no Universal number; out-of-range Universal numbers have no FDI tooth", () => {
  for (const n of [10, 19, 20, 49, 50, 55, 99]) assert.equal(fdiToUniversal(n), null);
  for (const n of [0, 33, 1.5, -1]) assert.equal(universalToFdi(n), null);
});

test("labels: Universal shows 1-32 and supernumerary teeth read near-tooth + 50; FDI keeps the stored code", () => {
  assert.equal(toothLabel(36, "UNIVERSAL"), "19");
  assert.equal(toothLabel(36, "FDI"), "36");
  assert.equal(toothLabel(18, "UNIVERSAL", true), "51");
  assert.equal(toothLabel(48, "UNIVERSAL", true), "82");
  assert.equal(toothLabel(11, "FDI", true), "S11");
  // switching back and forth never changes the stored number the label is computed from
  const stored = 46;
  assert.equal(toothLabel(stored, "FDI"), toothLabel(universalToFdi(Number(toothLabel(stored, "UNIVERSAL"))), "FDI"));
});

test("a new preset gets an upper-case code the server accepts", () => {
  assert.equal(presetCode("Next: obturation"), "NEXT_OBTURATION");
  assert.equal(presetCode("LA given"), "LA_GIVEN");
  assert.match(presetCode("  ?? "), /^[A-Z0-9_]{1,40}$/);
  assert.match(presetCode("x".repeat(80)), /^[A-Z0-9_]{1,40}$/);
});

test("drawer: numbering toggle only for setup:edit, uploads need clinical:create, phrases in a chair bar, no raw fetch", () => {
  const src = readFileSync(new URL("../../app/components/consult/DentalDrawer.tsx", import.meta.url), "utf8");
  assert.match(src, /canSetup = can\("setup:edit"\)/);
  assert.match(src, /canSetup \? \(\["FDI", "UNIVERSAL"\]/);
  assert.match(src, /canUpload = canChair && can\("clinical:create"\)/);
  assert.match(src, /\{canChair && \(\s*<div className=\{s\.chairBar\}/);
  assert.doesNotMatch(src, /authedFetch/);
});
