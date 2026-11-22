// FIN-025: the sale summary's tax note and the refund line total come from the server's minor-unit figures. `npm run -s test:unit`.
import { test } from "node:test";
import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import { refundLineMinor, taxNote } from "../../app/(clinic)/packages/packageSplit.ts";

test("an exempt component reads exempt and never taxable, whatever its amount", () => {
  assert.equal(taxNote("EXEMPT", 0), "exempt");
  assert.equal(taxNote("EXEMPT", 5), "exempt");
});
test("a taxed component reads taxable; a nil-rated or untaxed one reads no tax", () => {
  assert.equal(taxNote("TAXABLE", 44426), "taxable");
  assert.equal(taxNote("TAXABLE", 0), "no tax"); // Bill of Supply: nothing charged
  assert.equal(taxNote("NIL", 0), "no tax");
});
test("a refund line gives back its taxable value plus the tax reversed on it (WE-01 peel line)", () => {
  assert.equal(refundLineMinor({ refundTaxableMinor: 246816, refundTaxMinor: 44426 }), 291242);
  assert.equal(refundLineMinor({ refundTaxableMinor: 387266, refundTaxMinor: 0 }), 387266); // exempt sessions
});

const pk = (f) => readFileSync(new URL(`../../app/(clinic)/packages/${f}`, import.meta.url), "utf8");
test("T1-BUG-5: saving settings clears the previous error first, like the other page actions", () => {
  assert.match(pk("page.tsx"), /async function saveSettings\(\) \{\n[^\n]*\n\s*fe\.clear\(\);/);
});
test("T1-BUG-6: settings that did not load never get a Save, and saveSettings refuses to PATCH without them", () => {
  const page = pk("page.tsx"), tabs = pk("PackageTabs.tsx");
  assert.match(page, /async function saveSettings\(\) \{\n\s*if \(!settings\) return;/);
  assert.match(page, /onRetry=\{\(\) => void loadAll\(\)\}/);
  // the early return for `split === null` comes before the form (and its Save button)
  assert.ok(tabs.indexOf("if (!split) return") > 0 && tabs.indexOf("if (!split) return") < tabs.indexOf("onClick={onSave}"));
  assert.match(tabs, /if \(!split\) return[^\n]*ErrorState[^\n]*onRetry/);
});
