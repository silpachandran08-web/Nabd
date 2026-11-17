// PLT-013: device label header and the owner's break-glass review helpers. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { DEVICE_LABEL_MAX, defaultDeviceLabel, deviceLabelHeader, sanitiseDeviceLabel, setDeviceLabel } from "../../app/lib/deviceLabel.ts";
import { overdueGrants, reviewProblem } from "../../app/(clinic)/compliance/bgReviewRules.ts";
import { createClient } from "@nabd/api-client";

test("device labels are printable ASCII, no angle brackets, collapsed and at most 64 characters", () => {
  assert.equal(sanitiseDeviceLabel("  Front\tdesk \n PC 2 "), "Front desk PC 2");
  assert.equal(sanitiseDeviceLabel("<b>Desk</b>"), "b Desk /b");
  assert.equal(sanitiseDeviceLabel("काउंटर 1"), "1"); // fetch refuses non-Latin-1 header values
  assert.equal(sanitiseDeviceLabel("x".repeat(100)).length, DEVICE_LABEL_MAX);
});

test("the default label names the browser and OS, never the full user agent", () => {
  assert.equal(defaultDeviceLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"), "Chrome on Windows");
  assert.equal(defaultDeviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Safari/604.1"), "Safari on iOS");
  assert.equal(defaultDeviceLabel(""), "Browser");
});

test("a saved name is sent as X-Device-Label on every client call", async (t) => {
  const store = new Map();
  globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
  t.after(() => { delete globalThis.localStorage; });
  assert.equal(setDeviceLabel("  Front desk PC 2 "), "Front desk PC 2");
  assert.deepEqual(deviceLabelHeader(), { "X-Device-Label": "Front desk PC 2" });
  let sent;
  const client = createClient({ baseUrl: "http://x", getToken: () => null, onAuthFailure: () => {}, defaultHeaders: deviceLabelHeader,
    fetch: async (_u, init) => { sent = init.headers; return new Response("{}", { status: 200, headers: { "content-type": "application/json" } }); } });
  await client.post("/auth/break-glass/activate", { reasonCode: "other" });
  assert.equal(sent["X-Device-Label"], "Front desk PC 2");
});

test("overdue = unreviewed and ended 24 h ago, or already reminded by the server", () => {
  const now = Date.parse("2026-11-16T12:00:00Z");
  const g = (id, endedAt, outcome = null) => ({ grantId: id, endedAt, outcome });
  const items = [g("old", "2026-11-15T11:00:00Z"), g("new", "2026-11-16T11:00:00Z"), g("reminded", "2026-11-16T11:30:00Z"),
    g("done", "2026-11-10T11:00:00Z", "justified")];
  assert.deepEqual(overdueGrants(items, new Set(["reminded", "done"]), now).map((x) => x.grantId), ["old", "reminded"]);
});

test("review save errors map to a message key", () => {
  assert.equal(reviewProblem(409), "alreadyReviewed");
  assert.equal(reviewProblem(400), "noteRequired");
  assert.equal(reviewProblem(500), "saveError");
  assert.equal(reviewProblem(0), "saveError");
});
