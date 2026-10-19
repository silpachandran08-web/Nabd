// FEF-002: single-flight refresh, cross-tab lock + BroadcastChannel, returnTo validation, unlock messages.
// `npm run -s test:unit`.
import { test, beforeEach, after } from "node:test";
import assert from "node:assert/strict";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear() }; };
globalThis.localStorage = mem();
globalThis.sessionStorage = mem();
const loc = { pathname: "/patients/p1", search: "?tab=visit", replaced: [] };
loc.replace = (u) => loc.replaced.push(u);
globalThis.window = { location: loc, addEventListener() {} };
// Web Locks stand-in: one queue per name, like the browser's exclusive mode.
let tail = Promise.resolve();
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { locks: { request: (_n, fn) => { const r = tail.then(fn); tail = r.catch(() => {}); return r; } } } });
const realFetch = globalThis.fetch;
after(() => { globalThis.fetch = realFetch; });

const jwt = (exp, iat) => `h.${Buffer.from(JSON.stringify({ exp, iat })).toString("base64url")}.s`;
const now = () => Math.floor(Date.now() / 1000);
const mod = await import("../../app/lib/apiClient.ts");
const tabB = await import("../../app/lib/apiClient.ts?tabB");

let refreshCalls, apiCalls;
function mockServer({ refreshOk = true, delay = 20 } = {}) {
  let n = 0;
  globalThis.fetch = async (url, init) => {
    const auth = new Headers(init?.headers).get("Authorization");
    if (String(url).endsWith("/auth/refresh")) {
      refreshCalls++;
      await new Promise((r) => setTimeout(r, delay));
      if (!refreshOk) return new Response("{}", { status: 401 });
      n++;
      return new Response(JSON.stringify({ accessToken: jwt(now() + 900, now()) + n, refreshToken: `r${n}` }), { status: 200 });
    }
    apiCalls.push(auth);
    return new Response("{}", { status: auth?.endsWith(".s") && !auth.includes(".s1") && !auth.includes(".s2") ? 401 : 200 });
  };
}
// old token always 401s, tokens issued by the mock end in ".s<n>"
const OLD = jwt(now() + 600, now() - 100); // 80% mark not reached yet

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  localStorage.setItem("nabd_access_token", OLD);
  localStorage.setItem("nabd_refresh_token", "r0");
  refreshCalls = 0; apiCalls = []; loc.replaced.length = 0; loc.pathname = "/patients/p1"; loc.search = "?tab=visit";
});

test("50 concurrent 401s send exactly one refresh and each request is retried once", async () => {
  mockServer();
  const rs = await Promise.all(Array.from({ length: 50 }, (_, i) => mod.apiFetch(`/x/${i}`)));
  assert.equal(refreshCalls, 1);
  assert.ok(rs.every((r) => r.status === 200));
  assert.equal(apiCalls.length, 100); // 50 rejected + 50 retried
  assert.equal(new Set(apiCalls.slice(50)).size, 1); // all retries used the one new token
  assert.equal(localStorage.getItem("nabd_refresh_token"), "r1");
});

test("two tabs needing a token together: one refresh, the other gets it within 500 ms", async () => {
  mockServer({ delay: 50 });
  const t0 = Date.now();
  const [a, b] = await Promise.all([mod.apiFetch("/a"), tabB.apiFetch("/b")]);
  assert.equal(refreshCalls, 1);
  assert.equal(a.status, 200); assert.equal(b.status, 200);
  assert.ok(Date.now() - t0 < 500);
});

test("refresh rejected: 401 returned, tokens cleared, one redirect to /login with returnTo", async () => {
  mockServer({ refreshOk: false });
  const rs = await Promise.all([mod.apiFetch("/a"), mod.apiFetch("/b"), mod.apiFetch("/c")]);
  assert.ok(rs.every((r) => r.status === 401));
  assert.equal(localStorage.getItem("nabd_access_token"), null);
  mod.goLogin(); mod.goLogin(); mod.goLogin();
  assert.deepEqual(loc.replaced, ["/login?returnTo=%2Fpatients%2Fp1%3Ftab%3Dvisit"]);
  assert.equal(sessionStorage.getItem("nabd_return_to"), "/patients/p1?tab=visit");
});

test("proactive: a token past 80% of its life is refreshed before the request is sent", async () => {
  mockServer();
  localStorage.setItem("nabd_access_token", jwt(now() + 100, now() - 900)); // 90% used
  const r = await tabB.apiFetch("/y");
  assert.equal(refreshCalls, 1);
  assert.equal(apiCalls.length, 1); // no 401 round trip
  assert.equal(r.status, 200);
  assert.equal(mod.needsRefresh(jwt(now() + 600, now() - 100)), false);
  assert.equal(mod.needsRefresh("not-a-jwt"), false);
});

test("safeReturnTo accepts same-origin paths only", () => {
  for (const ok of ["/patients/p1", "/consult?entry=1#x"]) assert.equal(mod.safeReturnTo(ok), ok);
  for (const bad of ["https://evil.example", "//evil.example", "/\\evil.example", "http://x", "javascript:alert(1)", "evil", "", null, undefined, "/login", "/login?returnTo=/a", "/a\nb"]) assert.equal(mod.safeReturnTo(bad), null, String(bad));
});

test("takeReturnTo prefers a valid query param, ignores an evil one, falls back to sessionStorage once", () => {
  loc.search = "?returnTo=%2Fpatients%2Fp9";
  assert.equal(mod.takeReturnTo(), "/patients/p9");
  loc.search = "?returnTo=https%3A%2F%2Fevil.example";
  assert.equal(mod.takeReturnTo(), null);
  loc.search = "?returnTo=%2F%2Fevil.example";
  sessionStorage.setItem("nabd_return_to", "/billing");
  assert.equal(mod.takeReturnTo(), "/billing");
  assert.equal(mod.takeReturnTo(), null);
});

test("unlockMessage maps the three outcomes", () => {
  assert.equal(mod.unlockMessage(401, null, false), "Incorrect PIN");
  assert.equal(mod.unlockMessage(403, null, false), "Incorrect PIN");
  assert.equal(mod.unlockMessage(401, null, true), "Session expired, sign in again");
  assert.equal(mod.unlockMessage(429, "30", false), "Too many attempts, wait 30 s");
  assert.equal(mod.unlockMessage(429, null, false), "Too many attempts, wait 1 s");
});

// PLT-026: Idempotency-Key per POST action.
test("uuidv7 is a version-7 UUID with a sortable timestamp prefix", () => {
  const a = mod.uuidv7(1_700_000_000_000), b = mod.uuidv7(1_700_000_001_000);
  assert.match(a, /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.ok(a.slice(0, 13) < b.slice(0, 13));
  assert.notEqual(mod.uuidv7(), mod.uuidv7());
});

test("apiFetch adds one Idempotency-Key to a POST (same on the 401 replay), keeps a caller's, skips GET", async () => {
  const seen = [];
  globalThis.fetch = async (url, init) => {
    if (String(url).endsWith("/auth/refresh")) return new Response(JSON.stringify({ accessToken: jwt(now() + 900, now()) + ".s1", refreshToken: "r1" }), { status: 200 });
    const h = new Headers(init?.headers);
    seen.push({ m: init?.method ?? "GET", key: h.get("Idempotency-Key") });
    return new Response("{}", { status: h.get("Authorization")?.endsWith(".s1") ? 200 : 401 });
  };
  await mod.apiFetch("/billing/invoices/1/payments", { method: "POST", body: "{}" }); // OLD token 401s, replays after refresh
  assert.equal(seen.length, 2);
  assert.match(seen[0].key, /^[0-9a-f-]{36}$/);
  assert.equal(seen[1].key, seen[0].key);
  seen.length = 0;
  await mod.apiFetch("/x", { method: "POST", headers: { "Idempotency-Key": "mine" } });
  assert.equal(seen[0].key, "mine");
  seen.length = 0;
  await mod.apiFetch("/x");
  assert.equal(seen[0].key, null);
});
