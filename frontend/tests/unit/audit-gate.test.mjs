import test from "node:test";
import assert from "node:assert/strict";
import { evaluate } from "../../scripts/audit-gate.mjs";

const audit = (sev, id = "GHSA-aaaa-bbbb-cccc") => ({
  vulnerabilities: { pkg: { via: [{ severity: sev, url: `https://github.com/advisories/${id}`, title: "t" }, "other"] } },
});

test("high advisory fails and names the id", () => {
  const r = evaluate(audit("high"), [], "2026-01-01");
  assert.equal(r.failures.length, 1);
  assert.equal(r.failures[0].id, "GHSA-aaaa-bbbb-cccc");
});
test("critical fails", () => assert.equal(evaluate(audit("critical"), [], "2026-01-01").failures.length, 1));
test("moderate passes", () => assert.equal(evaluate(audit("moderate"), [], "2026-01-01").failures.length, 0));
test("allowlisted unexpired passes; boundary day still valid", () => {
  const allow = [{ id: "GHSA-aaaa-bbbb-cccc", expires: "2026-06-30" }];
  assert.equal(evaluate(audit("high"), allow, "2026-06-30").failures.length, 0);
});
test("expired allowlist entry fails again and is reported", () => {
  const allow = [{ id: "GHSA-aaaa-bbbb-cccc", expires: "2026-06-30" }];
  const r = evaluate(audit("high"), allow, "2026-07-01");
  assert.equal(r.failures.length, 1);
  assert.deepEqual(r.expired, ["GHSA-aaaa-bbbb-cccc"]);
});
