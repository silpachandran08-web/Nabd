// CLN-018: the footer text for the licensed drug knowledge base. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";

const { attributionLine } = await import("../../app/components/consult/drugKbLine.ts");

test("source name and data month are both shown", () => {
  assert.equal(attributionLine({ configured: true, source: "Example KB", dataMonth: "2026-10" }), "Drug data: Example KB October 2026");
  assert.equal(attributionLine({ configured: true, source: " Example KB ", dataMonth: "2027-01" }), "Drug data: Example KB January 2027");
});

test("nothing is shown when not configured or either part is missing or malformed", () => {
  for (const d of [null, undefined, {}, { configured: false, source: "X", dataMonth: "2026-10" },
    { configured: true, source: "", dataMonth: "2026-10" }, { configured: true, source: "X", dataMonth: null },
    { configured: true, source: "X", dataMonth: "2026-13" }, { configured: true, source: "X", dataMonth: "Oct 2026" }]) {
    assert.equal(attributionLine(d), null, JSON.stringify(d));
  }
});
