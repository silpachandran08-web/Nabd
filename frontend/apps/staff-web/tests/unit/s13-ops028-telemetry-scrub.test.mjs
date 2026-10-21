// OPS-028: client telemetry carries no query strings, ids, input values or names.
import { test } from "node:test";
import assert from "node:assert/strict";
import { scrubRoute, errorReport, vitalReport, stackHash } from "../../app/lib/telemetryScrub.ts";

test("route: query, fragment, host and ids are removed", () => {
  assert.equal(scrubRoute("https://app.nabd.health/patients/3f2b8c1e-1234-4abc-9def-0123456789ab?name=Asha+Rao&phone=9812345678#top"), "/patients/:id");
  assert.equal(scrubRoute("/queue/12345/notes?q=x"), "/queue/:id/notes");
  assert.equal(scrubRoute("https://app.nabd.health"), "/");
  assert.ok(scrubRoute("/" + "a".repeat(500)).length <= 200);
});

test("error report keeps the class name, never the message or input values", () => {
  const e = new TypeError("cannot read 'Asha Rao' phone 9812345678");
  const r = errorReport(e, "/patients/42?name=Asha");
  const text = JSON.stringify(r);
  assert.equal(r.kind, "error"); assert.equal(r.metric, "TypeError"); assert.equal(r.message, "");
  assert.ok(!/Asha|9812345678|name=/.test(text), text);
  assert.match(r.stackHash, /^[0-9a-f]+$/);
});

test("stack hash ignores line numbers and the message text", () => {
  const a = "Error: one\n    at f (https://x/app.js:10:5)\n    at g (https://x/app.js:20:7)";
  const b = "Error: other text\n    at f (https://x/app.js:99:1)\n    at g (https://x/app.js:3:3)";
  assert.equal(stackHash(a), stackHash(b));
});

test("vital report: sanitised name, rounded value, scrubbed route", () => {
  const r = vitalReport("LCP", 1234.5678, "/arrivals?token=abc");
  assert.deepEqual([r.kind, r.metric, r.value, r.route], ["vital", "LCP", 1234.57, "/arrivals"]);
});
