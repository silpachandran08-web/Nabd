// FEF-007: shared API client. Run: npm run -s test:unit
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { arrivalsSource, walkInDialogSource } from "./arrivalsSource.mjs";
import { createClient, intentKeys, ApiError, TimeoutError, uuidv7 } from "@nabd/api-client";

const json = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
function make(handler, o = {}) {
  const calls = [];
  let token = "t1"; let authFail = 0;
  const c = createClient({
    baseUrl: "http://x/v1", getToken: () => token, onAuthFailure: () => { authFail++; }, retryDelayMs: 1, language: () => "en-GB",
    fetch: async (url, init) => { calls.push({ url, init }); return handler(calls.length, url, init); }, ...o,
  });
  return { c, calls, setToken: (t) => { token = t; }, authFails: () => authFail };
}
const h = (call, name) => call.init.headers[name];

test("uuid v7 shape, version and time order", () => {
  const a = uuidv7(1_700_000_000_000), b = uuidv7(1_700_000_001_000);
  assert.match(a, /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.ok(a.slice(0, 13) < b.slice(0, 13));
  assert.equal(parseInt(a.replace(/-/g, "").slice(0, 12), 16), 1_700_000_000_000);
});

test("every call carries X-Request-Id, Accept-Language, bearer and an AbortSignal; Idempotency-Key only on POST/PUT/PATCH", async () => {
  const { c, calls } = make(() => json(200, { ok: 1 }));
  await c.get("/a"); await c.post("/b", { x: 1 }); await c.put("/c", {}); await c.patch("/d", {}); await c.delete("/e");
  const [g, p, u, pa, d] = calls;
  for (const k of calls) {
    assert.match(h(k, "X-Request-Id"), /^[0-9a-f-]{36}$/);
    assert.equal(h(k, "Accept-Language"), "en-GB");
    assert.equal(h(k, "Authorization"), "Bearer t1");
    assert.ok(k.init.signal instanceof AbortSignal);
  }
  assert.equal(h(g, "Idempotency-Key"), undefined);
  assert.equal(h(d, "Idempotency-Key"), undefined);
  for (const k of [p, u, pa]) assert.match(h(k, "Idempotency-Key"), /^[0-9a-f]{8}-[0-9a-f]{4}-7/);
  assert.equal(p.url, "http://x/v1/b");
  assert.equal(p.init.body, '{"x":1}');
});

test("same Idempotency-Key across automatic retries (network error, 503), different for a new call; max 2 retries", async () => {
  const { c, calls } = make((n) => { if (n === 1) throw new TypeError("fetch failed"); if (n === 2) return json(503, {}); return json(200, { id: "i" }); });
  assert.deepEqual(await c.post("/pay", { a: 1 }), { id: "i" });
  assert.equal(calls.length, 3);
  assert.equal(new Set(calls.map((k) => h(k, "Idempotency-Key"))).size, 1);
  assert.equal(new Set(calls.map((k) => h(k, "X-Request-Id"))).size, 3);
  await c.post("/pay", { a: 1 });
  assert.notEqual(h(calls[3], "Idempotency-Key"), h(calls[0], "Idempotency-Key"));
  const f = make(() => json(502, {}));
  await assert.rejects(f.c.post("/pay", {}), (e) => e instanceof ApiError && e.status === 502);
  assert.equal(f.calls.length, 3); // 1 + 2 retries
});

test("no automatic retry for GET, 4xx, 500 or a timeout", async () => {
  const g = make(() => json(503, {})); await assert.rejects(g.c.get("/a")); assert.equal(g.calls.length, 1);
  const p = make(() => json(500, {})); await assert.rejects(p.c.post("/a", {})); assert.equal(p.calls.length, 1);
  const q = make(() => json(409, { code: "X" })); await assert.rejects(q.c.post("/a", {})); assert.equal(q.calls.length, 1);
});

test("explicit idempotencyKey is used as given (user retry of the same intent)", async () => {
  const { c, calls } = make(() => json(200, {}));
  await c.post("/a", {}, { idempotencyKey: "k-1" }); await c.post("/a", {}, { idempotencyKey: "k-1" });
  assert.equal(h(calls[0], "Idempotency-Key"), "k-1"); assert.equal(h(calls[1], "Idempotency-Key"), "k-1");
});

test("intentKeys: stable while the body is the same, new after the body changes or done()", () => {
  const k = intentKeys();
  const a = k.get("pay", { amount: 5 });
  assert.equal(k.get("pay", { amount: 5 }), a);
  const b = k.get("pay", { amount: 6 }); assert.notEqual(b, a);
  k.done("pay"); assert.notEqual(k.get("pay", { amount: 6 }), b);
  assert.notEqual(k.get("other", { amount: 6 }), k.get("pay", { amount: 6 }));
});

test("Problem+JSON parsed into ApiError {status, code, title, detail, errors[]} with fieldErrors()", async () => {
  const { c } = make(() => json(400, { title: "Validation failed", detail: "name: required", code: "VALIDATION", errors: [{ field: "name", message: "required" }, { field: "phone", message: "bad" }, { field: "name", message: "second" }, { nofield: 1 }], candidates: [1] }));
  const e = await c.post("/patients", {}).catch((x) => x);
  assert.ok(e instanceof ApiError);
  assert.equal(e.status, 400); assert.equal(e.code, "VALIDATION"); assert.equal(e.title, "Validation failed"); assert.equal(e.detail, "name: required");
  assert.equal(e.errors.length, 3);
  assert.deepEqual(e.fieldErrors(), { name: "required", phone: "bad" });
  assert.deepEqual(e.problem.candidates, [1]);
  const bare = await make(() => new Response("<html>", { status: 502 })).c.get("/x").catch((x) => x);
  assert.ok(bare instanceof ApiError); assert.equal(bare.status, 502); assert.deepEqual(bare.errors, []);
});

test("204 resolves undefined", async () => assert.equal(await make(() => new Response(null, { status: 204 })).c.delete("/a"), undefined));

test("401 -> one single-flight refresh for parallel calls, then each replays once with the new token", async () => {
  let refreshes = 0, tok = "old"; const calls = [];
  const c = createClient({
    baseUrl: "http://x", getToken: () => tok, onAuthFailure() {},
    fetch: async (u, i) => { calls.push(i); return i.headers.Authorization === "Bearer new" ? json(200, { ok: true }) : json(401, {}); },
    refresh: async () => { refreshes++; await new Promise((r) => setTimeout(r, 10)); tok = "new"; return true; },
  });
  const r = await Promise.all([c.get("/a"), c.get("/b"), c.get("/c")]);
  assert.equal(refreshes, 1);
  assert.deepEqual(r, [{ ok: true }, { ok: true }, { ok: true }]);
  assert.equal(calls.length, 6);
});

test("401 that survives refresh (or refresh refused) calls onAuthFailure and throws ApiError 401", async () => {
  const a = make(() => json(401, { title: "Unauthorized" }), { refresh: async () => false });
  await assert.rejects(a.c.get("/a"), (e) => e.status === 401);
  assert.equal(a.authFails(), 1); assert.equal(a.calls.length, 1);
  const b = make(() => json(401, {}), { refresh: async () => true });
  await assert.rejects(b.c.get("/a"));
  assert.equal(b.authFails(), 1); assert.equal(b.calls.length, 2); // replayed once, not looped
});

test("shouldRefresh refreshes before the call", async () => {
  let tok = "old", n = 0;
  const seen = [];
  const c = createClient({ baseUrl: "http://x", getToken: () => tok, onAuthFailure() {}, shouldRefresh: () => tok === "old", refresh: async () => { n++; tok = "new"; return true; }, fetch: async (u, i) => { seen.push(i.headers.Authorization); return json(200, {}); } });
  await c.get("/a"); assert.equal(n, 1); assert.deepEqual(seen, ["Bearer new"]);
});

test("a request over timeoutMs aborts with a typed TimeoutError (not retried)", async () => {
  let n = 0;
  const slow = (_n, _u, init) => new Promise((_, rej) => { n++; init.signal.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))); });
  const { c } = make(slow, { timeoutMs: 30 });
  const t0 = Date.now();
  const e = await c.post("/pay", {}).catch((x) => x);
  assert.ok(e instanceof TimeoutError); assert.equal(e.timeoutMs, 30); assert.equal(n, 1);
  assert.ok(Date.now() - t0 < 1000);
});

test("default timeout is 15 s", async () => {
  const { setTimeout: real } = globalThis; const seen = [];
  globalThis.setTimeout = (f, ms, ...a) => { seen.push(ms); return real(f, ms, ...a); };
  try { await make(() => json(200, {})).c.get("/a"); } finally { globalThis.setTimeout = real; }
  assert.ok(seen.includes(15000));
});

test("a caller AbortSignal aborts the request and is not retried or reported as a timeout", async () => {
  const ctl = new AbortController(); let n = 0;
  const { c } = make((_n, _u, init) => new Promise((_, rej) => { n++; init.signal.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))); }));
  const p = c.post("/a", {}, { signal: ctl.signal }); setTimeout(() => ctl.abort(), 10);
  const e = await p.catch((x) => x);
  assert.equal(e.name, "AbortError"); assert.equal(n, 1);
});

// Contract: the endpoints the migrated flows call exist in api/openapi.yaml, except the T-089 gaps (the payments POST was added by FIN-001).
test("contract: migrated endpoints are in api/openapi.yaml (known gaps listed)", () => {
  const spec = readFileSync(new URL("../../../../../api/openapi.yaml", import.meta.url), "utf8");
  const has = (p) => spec.split("\n").some((l) => l === `  ${p}:`);
  for (const p of ["/billing/checkout/{queueEntryId}", "/billing/otc-charges", "/billing/otc-checkout", "/queue/check-in", "/patients", "/queue/{id}/status", "/departments/{id}/flow", "/staff/roster", "/queue"]) assert.ok(has(p), p);
  const gaps = ["/billing/checkout/{queueEntryId}/invoice", "/billing/invoices/{id}/payments", "/queue/wait-estimate"].filter((p) => !has(p));
  assert.deepEqual(gaps, ["/billing/checkout/{queueEntryId}/invoice", "/queue/wait-estimate"], "FIN-001 added /billing/invoices/{id}/payments; if this fails a gap was closed: drop it from types.ts TODO(T-089)");
});

// Acceptance 3: the three flows carry no fetch(, API_BASE or local response types.
test("migrated screens contain 0 fetch(, 0 API_BASE and 0 hand-written response types", () => {
  for (const f of ["app/(clinic)/checkout/[queueEntryId]/page.tsx", "app/(clinic)/checkout/otc/page.tsx", "app/components/arrivals/CheckInDialog.tsx"]) {
    const src = readFileSync(new URL(`../../${f}`, import.meta.url), "utf8");
    assert.doesNotMatch(src, /fetch\(/i, f); assert.doesNotMatch(src, /API_BASE/, f);
    assert.doesNotMatch(src, /^\s*(export )?type (Invoice|Charge|Payment|LineItem|Problem|QueueEntry|CheckoutContext|OtcContext|FlowStep|PrescribedItem)\b/m, f);
    assert.doesNotMatch(src, /\.json\(\)/, f);
  }
  const arr = arrivalsSource(); // FEF-028: page + components/arrivals
  assert.doesNotMatch(arr, /API_BASE/);
  const wd = walkInDialogSource();
  const walk = wd.slice(wd.indexOf("async function registerAndCheckIn"), wd.indexOf("function onField"));
  assert.doesNotMatch(walk, /fetch\(|\.json\(\)/i);
});
