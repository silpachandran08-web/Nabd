// PLT-007: open tabs are signed out when the session is revoked (other tab saw it, or the 60 s probe gets a 401
// that survives refresh: 401 TENANT_INACTIVE / SESSION_MAX_AGE / revoked). `npm run -s test:unit`.
import { test, beforeEach, after } from "node:test";
import assert from "node:assert/strict";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear() }; };
globalThis.localStorage = mem();
globalThis.sessionStorage = mem();
const loc = { pathname: "/queue", search: "", replaced: [] };
loc.replace = (u) => loc.replaced.push(u);
globalThis.window = { location: loc, addEventListener() {} };
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { locks: { request: (_n, fn) => fn() } } });
const realFetch = globalThis.fetch;
after(() => { globalThis.fetch = realFetch; });

const jwt = (exp, iat) => `h.${Buffer.from(JSON.stringify({ exp, iat })).toString("base64url")}.s`;
const now = () => Math.floor(Date.now() / 1000);
let n = 0;
const fresh = () => import(`../../app/lib/apiClient.ts?plt007-${n++}`); // new module = new tab (own "leaving" flag)

let calls;
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); loc.replaced.length = 0; calls = [];
});

function server(apiStatus, refreshStatus) {
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).endsWith("/auth/refresh")) {
      return new Response(JSON.stringify({ type: "x/TENANT_INACTIVE" }), { status: refreshStatus });
    }
    return new Response("{}", { status: apiStatus });
  };
}

test("probe interval is at most 60 s", async () => {
  const m = await fresh();
  assert.ok(m.PROBE_MS <= 60_000);
});

test("another tab removing the access token signs this tab out with returnTo", async () => {
  const m = await fresh();
  localStorage.setItem("nabd_refresh_token", "r");
  m.onStorage({ key: "nabd_access_token", newValue: null });
  assert.deepEqual(loc.replaced, ["/login?returnTo=%2Fqueue"]);
  assert.equal(localStorage.getItem("nabd_refresh_token"), null);
});

test("a rotated token in another tab does not sign out", async () => {
  const m = await fresh();
  m.onStorage({ key: "nabd_access_token", newValue: jwt(now() + 900, now()) });
  m.onStorage({ key: "other", newValue: null });
  assert.deepEqual(loc.replaced, []);
});

test("probe: revoked session (401, refresh 401 TENANT_INACTIVE) signs out and clears tokens", async () => {
  const m = await fresh();
  localStorage.setItem("nabd_access_token", jwt(now() + 600, now() - 100));
  localStorage.setItem("nabd_refresh_token", "r");
  server(401, 401);
  await m.probeSession();
  assert.ok(calls.some((u) => u.endsWith("/auth/sessions")));
  assert.ok(calls.some((u) => u.endsWith("/auth/refresh")));
  assert.deepEqual(loc.replaced, ["/login?returnTo=%2Fqueue"]);
  assert.equal(localStorage.getItem("nabd_access_token"), null);
});

test("probe: live session stays signed in", async () => {
  const m = await fresh();
  localStorage.setItem("nabd_access_token", jwt(now() + 600, now() - 100));
  server(200, 200);
  await m.probeSession();
  assert.deepEqual(loc.replaced, []);
});

test("probe: server error or offline keeps the session (no sign-out on 5xx)", async () => {
  const m = await fresh();
  localStorage.setItem("nabd_access_token", jwt(now() + 600, now() - 100));
  server(503, 200);
  await m.probeSession();
  globalThis.fetch = async () => { throw new TypeError("offline"); };
  await m.probeSession();
  assert.deepEqual(loc.replaced, []);
  assert.ok(localStorage.getItem("nabd_access_token"));
});

test("probe: signed-out tab makes no request", async () => {
  const m = await fresh();
  server(200, 200);
  await m.probeSession();
  assert.deepEqual(calls, []);
});
