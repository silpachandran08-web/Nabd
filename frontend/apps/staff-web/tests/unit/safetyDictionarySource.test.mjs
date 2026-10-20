// CLN-004: the line shown under each Rx warning. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { sourceLine } from "../../app/components/consult/safetyDictionary.ts";

test("source and label make the line", () => {
  assert.equal(sourceLine({ source: "Nabd CSO dictionary", label: "IN v1" }), "Source: Nabd CSO dictionary IN v1");
});

test("a missing part or a missing body gives no line", () => {
  for (const d of [null, undefined, {}, { source: "Nabd CSO dictionary" }, { label: "IN v1" }, { source: "", label: "IN v1" }]) {
    assert.equal(sourceLine(d), null);
  }
});
