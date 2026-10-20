// S10 independent QA for FEF-007 (shared API client). Written from the story's acceptance criteria. Run: npm run -s test:unit
import { test, mock } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createClient, ApiError, TimeoutError, intentKeys, uuidv7 } from "@nabd/api-client";

const UUID7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const res = (status, body, raw) => new Response(raw ?? (body === undefined ? null : JSON.stringify(body)), { status, headers: { "Content-Type": "application/json" } });
function make(handler, o = {}) {
  const calls = [];
  const s = { token: "t1", authFail: 0, refreshes: 0 };
  const c = createClient({
    baseUrl: "http://x/v1", getToken: () => s.token, onAuthFailure: () => { s.authFail++; }, retryDelayMs: 1, language: () => "en-GB",
    fetch: async (url, init) => { calls.push({ url, init }); return handler(calls.length, url, init); }, ...o,
  });
  return { c, calls, s };
}
const hd = (call, n) => call.init.headers[n];

// AC1 surface -----------------------------------------------------------------------------------
test("AC1: exports createClient with ApiError/TimeoutError and the verbs", () => {
  const { c } = make(() => res(200, {}));
  for (const m of ["request", "get", "post", "put", "patch", "delete"]) assert.equal(typeof c[m], "function", m);
  assert.equal(typeof createClient, "function");
  assert.ok(new ApiError(400) instanceof Error);
  assert.ok(new TimeoutError(15000) instanceof Error);
});

test("AC1: Problem+JSON becomes a typed ApiError {status, code, title, detail, errors[]}", async () => {
  const { c } = make(() => res(422, { type: "x", title: "Validation failed", status: 422, detail: "phone: bad", code: "VALIDATION", errors: [{ field: "phone", message: "bad" }, { field: "name", message: "required" }, { nofield: 1 }] }));
  await assert.rejects(c.post("/p", {}), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 422); assert.equal(e.code, "VALIDATION"); assert.equal(e.title, "Validation failed"); assert.equal(e.detail, "phone: bad");
    assert.deepEqual(e.errors, [{ field: "phone", message: "bad" }, { field: "name", message: "required" }]);
    assert.deepEqual(e.fieldErrors(), { phone: "bad", name: "required" });
    assert.equal(e.message, "phone: bad");
    return true;
  });
});

test("AC1: a non-JSON or empty error body still yields an ApiError with the status", async () => {
  for (const make1 of [() => new Response("<html>bad gateway</html>", { status: 500 }), () => new Response(null, { status: 404 })]) {
    const { c } = make(make1);
    await assert.rejects(c.get("/p"), (e) => e instanceof ApiError && [500, 404].includes(e.status) && Array.isArray(e.errors) && e.errors.length === 0);
  }
});

test("AC1: the same field reported twice keeps the first message", async () => {
  const { c } = make(() => res(400, { errors: [{ field: "a", message: "first" }, { field: "a", message: "second" }] }));
  await assert.rejects(c.post("/p", {}), (e) => e.fieldErrors().a === "first");
});

test("AC1: 2xx JSON is returned parsed, 204 returns undefined", async () => {
  assert.deepEqual(await make(() => res(200, { a: 1 })).c.get("/p"), { a: 1 });
  assert.equal(await make(() => new Response(null, { status: 204 })).c.delete("/p"), undefined);
});

test("AC1: every call has an AbortSignal; a caller abort rejects and is not retried", async () => {
  const ctl = new AbortController();
  const { c, calls } = make((n, u, init) => new Promise((_, rej) => init.signal.addEventListener("abort", () => rej(new TypeError("aborted")))));
  const p = c.post("/p", {}, { signal: ctl.signal });
  await new Promise((r) => setTimeout(r, 10));
  ctl.abort();
  await assert.rejects(p);
  assert.equal(calls.length, 1);
  assert.ok(calls[0].init.signal instanceof AbortSignal);
  const pre = new AbortController(); pre.abort();
  const second = make((n, u, init) => (init.signal.aborted ? Promise.reject(new TypeError("aborted")) : res(200, {})));
  await assert.rejects(second.c.get("/p", { signal: pre.signal }));
});

test("AC1: single-flight refresh: five parallel 401s refresh once and all succeed with the new token", async () => {
  let refreshed = 0; let live = "old";
  const { c, calls, s } = make((n, u, init) => (hd({ init }, "Authorization") === "Bearer new" ? res(200, { ok: 1 }) : res(401, { title: "expired" })),
    { getToken: () => live, refresh: async () => { refreshed++; await new Promise((r) => setTimeout(r, 20)); live = "new"; return true; } });
  const out = await Promise.all([1, 2, 3, 4, 5].map((i) => c.get(`/p${i}`)));
  assert.equal(out.length, 5);
  assert.equal(refreshed, 1);
  assert.equal(s.authFail, 0);
  assert.equal(calls.filter((k) => hd(k, "Authorization") === "Bearer new").length, 5);
});

test("AC1: refresh refused -> onAuthFailure runs and the caller sees a 401 ApiError", async () => {
  const { c, s } = make(() => res(401, { title: "expired" }), { refresh: async () => false });
  await assert.rejects(c.get("/p"), (e) => e instanceof ApiError && e.status === 401);
  assert.ok(s.authFail >= 1);
});

test("AC1: a 401 that survives a successful refresh ends the session instead of looping", async () => {
  const { c, calls, s } = make(() => res(401, { title: "still no" }), { refresh: async () => true });
  await assert.rejects(c.get("/p"), (e) => e.status === 401);
  assert.equal(calls.length, 2);
  assert.ok(s.authFail >= 1);
});

test("AC1: shouldRefresh triggers a refresh before the call", async () => {
  let live = "old"; let refreshed = 0;
  const { c, calls } = make(() => res(200, {}), { getToken: () => live, shouldRefresh: () => true, refresh: async () => { refreshed++; live = "fresh"; return true; } });
  await c.get("/p");
  assert.equal(refreshed, 1);
  assert.equal(hd(calls[0], "Authorization"), "Bearer fresh");
});

test("AC1: no token means no Authorization header; body adds Content-Type only when present", async () => {
  const { c, calls } = make(() => res(200, {}), { getToken: () => null });
  await c.get("/a"); await c.post("/b", { x: 1 });
  assert.equal(hd(calls[0], "Authorization"), undefined);
  assert.equal(hd(calls[0], "Content-Type"), undefined);
  assert.equal(hd(calls[1], "Content-Type"), "application/json");
});

// AC2 idempotency key -----------------------------------------------------------------------------
test("AC2: POST/PUT/PATCH carry a UUIDv7 Idempotency-Key, GET/DELETE do not", async () => {
  const { c, calls } = make(() => res(200, {}));
  await c.get("/g"); await c.delete("/d"); await c.post("/p", {}); await c.put("/u", {}); await c.patch("/a", {});
  assert.equal(hd(calls[0], "Idempotency-Key"), undefined);
  assert.equal(hd(calls[1], "Idempotency-Key"), undefined);
  for (const k of calls.slice(2)) assert.match(hd(k, "Idempotency-Key"), UUID7);
});

test("AC2: the key is the same across automatic retries of one call, different for the next action", async () => {
  const { c, calls } = make((n) => (n < 3 ? res(503, { title: "busy" }) : res(200, { ok: 1 })));
  await c.post("/pay", { a: 1 });
  assert.equal(calls.length, 3);
  assert.equal(new Set(calls.map((k) => hd(k, "Idempotency-Key"))).size, 1);
  await c.post("/pay", { a: 1 });
  assert.notEqual(hd(calls[3], "Idempotency-Key"), hd(calls[0], "Idempotency-Key"));
});

test("AC2: the key survives the 401 -> refresh -> replay", async () => {
  let live = "old";
  const { c, calls } = make((n, u, init) => (hd({ init }, "Authorization") === "Bearer new" ? res(201, { ok: 1 }) : res(401, {})),
    { getToken: () => live, refresh: async () => { live = "new"; return true; } });
  await c.post("/pay", { a: 1 });
  assert.equal(calls.length, 2);
  assert.equal(hd(calls[0], "Idempotency-Key"), hd(calls[1], "Idempotency-Key"));
});

test("AC2: a caller supplied key is used as is (user retry after a timeout)", async () => {
  const { c, calls } = make(() => res(200, {}));
  await c.post("/p", {}, { idempotencyKey: "fixed-key-1" });
  assert.equal(hd(calls[0], "Idempotency-Key"), "fixed-key-1");
});

test("AC2: intentKeys: same body -> same key; changed body -> new key; done() -> new key", () => {
  const k = intentKeys();
  const a = k.get("pay", { amount: 5 });
  assert.equal(k.get("pay", { amount: 5 }), a);
  const b = k.get("pay", { amount: 6 });
  assert.notEqual(b, a);
  k.done("pay");
  assert.notEqual(k.get("pay", { amount: 6 }), b);
  assert.notEqual(k.get("other", { amount: 6 }), k.get("pay", { amount: 6 }));
  assert.match(a, UUID7);
});

test("AC2: uuidv7 is version 7, variant 10, time-ordered and unique", () => {
  const ids = new Set(Array.from({ length: 500 }, () => uuidv7()));
  assert.equal(ids.size, 500);
  for (const id of ids) assert.match(id, UUID7);
  assert.ok(uuidv7(1_000) < uuidv7(2_000_000_000_000));
});

test("headers: X-Request-Id differs per request, Accept-Language is sent", async () => {
  const { c, calls } = make(() => res(200, {}));
  await c.get("/a"); await c.get("/b");
  assert.match(hd(calls[0], "X-Request-Id"), /^[0-9a-f-]{36}$/);
  assert.notEqual(hd(calls[0], "X-Request-Id"), hd(calls[1], "X-Request-Id"));
  assert.equal(hd(calls[0], "Accept-Language"), "en-GB");
});

// NFR retry policy -------------------------------------------------------------------------------
test("NFR: POST retries 502/503/504 at most twice (3 calls) then surfaces the ApiError", async () => {
  for (const st of [502, 503, 504]) {
    const { c, calls } = make(() => res(st, { title: "down" }));
    await assert.rejects(c.post("/p", {}), (e) => e.status === st);
    assert.equal(calls.length, 3, `status ${st}`);
  }
});

test("NFR: 400/401-after-refresh/409/422/500 are never retried", async () => {
  for (const st of [400, 403, 404, 409, 422, 500]) {
    const { c, calls } = make(() => res(st, { title: "no" }));
    await assert.rejects(c.post("/p", {}));
    assert.equal(calls.length, 1, `status ${st}`);
  }
});

test("NFR: a network TypeError on a POST is retried (max 2) with the same key; other errors are not", async () => {
  let n = 0;
  const flaky = make(() => { n++; if (n < 3) throw new TypeError("Failed to fetch"); return res(200, { ok: 1 }); });
  assert.deepEqual(await flaky.c.post("/p", {}), { ok: 1 });
  assert.equal(new Set(flaky.calls.map((k) => hd(k, "Idempotency-Key"))).size, 1);
  const dead = make(() => { throw new TypeError("Failed to fetch"); });
  await assert.rejects(dead.c.post("/p", {}), TypeError);
  assert.equal(dead.calls.length, 3);
  const weird = make(() => { throw new RangeError("boom"); });
  await assert.rejects(weird.c.post("/p", {}), RangeError);
  assert.equal(weird.calls.length, 1);
});

test("NFR: GET is not retried on 503 (only idempotent-keyed calls are)", async () => {
  const { c, calls } = make(() => res(503, { title: "busy" }));
  await assert.rejects(c.get("/p"), (e) => e.status === 503);
  assert.equal(calls.length, 1);
});

// AC5 timeout ------------------------------------------------------------------------------------
test("AC5: default timeout is 15 s: aborts with TimeoutError, not earlier, not retried", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    const { c, calls } = make((n, u, init) => new Promise((_, rej) => init.signal.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError")))));
    let outcome = "pending";
    const p = c.post("/pay", { a: 1 }).then(() => { outcome = "ok"; }, (e) => { outcome = e; });
    await Promise.resolve();
    mock.timers.tick(14_999);
    await new Promise((r) => setImmediate(r));
    assert.equal(outcome, "pending", "must not abort before 15 s");
    mock.timers.tick(1);
    await p;
    assert.ok(outcome instanceof TimeoutError, String(outcome));
    assert.equal(outcome.timeoutMs, 15000);
    assert.equal(outcome.name, "TimeoutError");
    assert.equal(calls.length, 1, "a timeout must not be auto-retried: the server may have acted");
  } finally { mock.timers.reset(); }
});

test("AC5: timeoutMs is configurable and a fast response clears the timer", async () => {
  const { c } = make(async () => res(200, { ok: 1 }), { timeoutMs: 50 });
  assert.deepEqual(await c.get("/p"), { ok: 1 });
  const slow = make((n, u, init) => new Promise((_, rej) => init.signal.addEventListener("abort", () => rej(new DOMException("a", "AbortError")))), { timeoutMs: 30 });
  await assert.rejects(slow.c.get("/p"), TimeoutError);
});

// AC3 static: the migrated flows are free of hand-rolled fetch logic ---------------------------------
const src = (p) => readFileSync(new URL(`../../${p}`, import.meta.url), "utf8");
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");
for (const f of ["app/checkout/[queueEntryId]/page.tsx", "app/checkout/otc/page.tsx", "app/components/arrivals/CheckInDialog.tsx"]) {
  test(`AC3: ${f} has no fetch(, API_BASE, hand-written Problem type or raw res.json()`, () => {
    const s = strip(src(f));
    assert.doesNotMatch(s, /\bfetch\s*\(/); assert.doesNotMatch(s, /API_BASE/); assert.doesNotMatch(s, /type Problem\b/);
    assert.doesNotMatch(s, /authedFetch/); assert.doesNotMatch(s, /\bres\.json\(\)/);
  });
}
test("AC3: the walk-in registration function in arrivals/page.tsx uses the client, not fetch", () => {
  const s = strip(src("app/arrivals/page.tsx"));
  const a = s.indexOf("async function registerAndCheckIn");
  const b = s.indexOf("function openVitalsModal");
  assert.ok(a > 0 && b > a);
  const body = s.slice(a, b);
  assert.doesNotMatch(body, /\bfetch\s*\(|authedFetch|API_BASE/);
  assert.match(body, /api\.post/);
});
test("AC3: the client itself has no module-level API_BASE constant and exports its types file", () => {
  assert.doesNotMatch(strip(src("../../packages/api-client/index.ts")), /API_BASE/);
  assert.match(src("../../packages/api-client/types.ts"), /export type Invoice\b/);
});
