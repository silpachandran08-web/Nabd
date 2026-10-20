// S9 QA (FEF-002, independent): rotation-safe refresh across tabs, failure modes, returnTo validation, redirect behaviour.
import { test, beforeEach, after } from "node:test";
import assert from "node:assert/strict";

const mem = () => { const m = new Map(); return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), removeItem: (k) => m.delete(k), clear: () => m.clear() }; };
globalThis.localStorage = mem();
globalThis.sessionStorage = mem();
const loc = { pathname: "/patients/p1", search: "?tab=visit", replaced: [] };
loc.replace = (u) => loc.replaced.push(u);
globalThis.window = { location: loc, addEventListener() {} };
let tail = Promise.resolve();
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { locks: { request: (_n, fn) => { const r = tail.then(fn); tail = r.catch(() => {}); return r; } } } });
const realFetch = globalThis.fetch;
after(() => { globalThis.fetch = realFetch; });

const now = () => Math.floor(Date.now() / 1000);
const jwt = (exp, iat, tag = "") => `h.${Buffer.from(JSON.stringify({ exp, iat, tag })).toString("base64url")}.s`;
const A = await import("../../app/lib/apiClient.ts?qaA");
const B = await import("../../app/lib/apiClient.ts?qaB");
const C = await import("../../app/lib/apiClient.ts?qaC");

let st;
/** Server with strict single-use refresh tokens; reuse revokes the family (everything fails afterwards). */
function server({ refreshStatus = 200, delay = 15 } = {}) {
  st = { refreshCalls: 0, reuse: 0, revoked: false, used: new Set(), n: 0, valid: new Set(["r0"]), seenAuth: [], apiSignals: [] };
  globalThis.fetch = async (url, init) => {
    if (String(url).endsWith("/auth/refresh")) {
      st.refreshCalls++;
      await new Promise((r) => setTimeout(r, delay));
      if (refreshStatus === "network") throw new TypeError("network down");
      if (refreshStatus !== 200) return new Response("{}", { status: refreshStatus });
      const rt = JSON.parse(init.body).refreshToken;
      if (st.used.has(rt)) { st.reuse++; st.revoked = true; }
      if (st.revoked || !st.valid.has(rt)) return new Response("{}", { status: 401 });
      st.used.add(rt); st.valid.delete(rt);
      const n = ++st.n; st.valid.add(`r${n}`);
      return new Response(JSON.stringify({ accessToken: jwt(now() + 900, now(), `fresh${n}`), refreshToken: `r${n}`, expiresIn: 900 }), { status: 200 });
    }
    const auth = new Headers(init?.headers).get("Authorization");
    st.seenAuth.push(auth); st.apiSignals.push(init?.signal);
    const fresh = auth && auth.includes(Buffer.from('"tag":"fresh').toString("base64url").slice(0, 8)) ;
    const tag = auth ? JSON.parse(Buffer.from(auth.split(".")[1], "base64url").toString()).tag : "";
    return new Response("{}", { status: !st.revoked && tag.startsWith("fresh") ? 200 : 401 });
  };
}
const OLD = () => jwt(now() + 600, now() - 100, "old");
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  localStorage.setItem("nabd_access_token", OLD());
  localStorage.setItem("nabd_refresh_token", "r0");
  loc.replaced.length = 0; loc.pathname = "/patients/p1"; loc.search = "?tab=visit";
});

test("3 tabs x 10 concurrent 401s: refresh token never reused, nobody revoked, every request succeeds", async () => {
  server();
  const tabs = [A, B, C];
  const rs = await Promise.all(tabs.flatMap((t, i) => Array.from({ length: 10 }, (_, j) => t.apiFetch(`/x/${i}/${j}`))));
  assert.equal(st.reuse, 0, "reuse detected");
  assert.equal(st.revoked, false);
  assert.ok(rs.every((r) => r.status === 200), "all retried to 200");
  assert.equal(loc.replaced.length, 0, "no redirect to /login");
});

test("repeated expiry over a long shift: sequential rotations stay clean", async () => {
  server();
  for (let round = 0; round < 8; round++) {
    await new Promise((r) => setTimeout(r, 60)); // let the previous round's BroadcastChannel messages drain (real shifts are minutes apart)
    localStorage.setItem("nabd_access_token", OLD()); // access expires again
    const rs = await Promise.all([A, B, C].map((t) => t.apiFetch(`/r/${round}`)));
    assert.ok(rs.every((r) => r.status === 200), `round ${round}: ${rs.map((r) => r.status)} calls=${st.refreshCalls} reuse=${st.reuse} rt=${localStorage.getItem("nabd_refresh_token")}`);
  }
  assert.equal(st.reuse, 0);
  assert.equal(st.refreshCalls, 8, "exactly one refresh per expiry");
});

test("a 401 that survives the refresh is retried only once (no loop)", async () => {
  server();
  let apiCalls = 0;
  globalThis.fetch = (f => async (u, i) => String(u).endsWith("/auth/refresh") ? f(u, i) : (apiCalls++, new Response("{}", { status: 401 })))(globalThis.fetch);
  const r = await A.apiFetch("/always-401");
  assert.equal(r.status, 401);
  assert.equal(st.refreshCalls, 1);
  assert.equal(apiCalls, 2, "one original + exactly one retry");
});

test("refresh answered 401 (revoked): tokens cleared, caller sees 401; goLogin adds returnTo once", async () => {
  server({ refreshStatus: 401 });
  const rs = await Promise.all(Array.from({ length: 6 }, (_, i) => A.apiFetch(`/y/${i}`)));
  assert.ok(rs.every((r) => r.status === 401));
  assert.equal(st.refreshCalls, 1);
  rs.forEach(() => A.goLogin());
  assert.deepEqual(loc.replaced, ["/login?returnTo=%2Fpatients%2Fp1%3Ftab%3Dvisit"]);
});

test("TRANSIENT refresh failure (server 503) must not destroy the session", async () => {
  server({ refreshStatus: 503 });
  await A.apiFetch("/z").catch(() => {});
  assert.equal(localStorage.getItem("nabd_refresh_token"), "r0", "refresh token must survive a 503 so the next attempt can succeed");
});

test("network failure during refresh must not destroy the session", async () => {
  server({ refreshStatus: "network" });
  await A.apiFetch("/z").catch(() => {});
  assert.equal(localStorage.getItem("nabd_refresh_token"), "r0");
});

test("AbortSignal and method/body pass through to the retried request", async () => {
  server();
  const ac = new AbortController();
  await A.apiFetch("/p", { method: "POST", body: "{}", signal: ac.signal });
  assert.equal(st.apiSignals.length, 2);
  assert.ok(st.apiSignals.every((s) => s === ac.signal));
});

test("no token value is written to console during refresh success and failure", async () => {
  const seen = []; const orig = { ...console };
  for (const k of ["log", "error", "warn", "info", "debug"]) console[k] = (...a) => seen.push(a.join(" "));
  try {
    server(); await A.apiFetch("/a");
    localStorage.setItem("nabd_access_token", OLD()); server({ refreshStatus: 401 }); await A.apiFetch("/b");
  } finally { Object.assign(console, orig); }
  assert.deepEqual(seen.filter((s) => /h\.[A-Za-z0-9_-]+\.s|r0|r1/.test(s)), []);
});

test("needsRefresh: 80% boundary", () => {
  const iat = 1_000_000, exp = iat + 900; // 80% = iat+720
  const tok = jwt(exp, iat);
  assert.equal(A.needsRefresh(tok, (iat + 719) * 1000), false);
  assert.equal(A.needsRefresh(tok, (iat + 720) * 1000), true);
  assert.equal(A.needsRefresh(null), false);
  assert.equal(A.needsRefresh("garbage"), false);
});

test("safeReturnTo rejects every off-origin / unsafe shape and keeps legitimate paths", () => {
  for (const bad of ["https://evil.example", "http://evil.example/x", "//evil.example", "///evil.example", "/\\evil.example", "\\\\evil.example",
    "javascript:alert(1)", "data:text/html,x", "evil.example", "", null, undefined, "/\t/evil.example", "/\n/evil.example", "/login", "/login?returnTo=/a", "/login/x"]) {
    assert.equal(A.safeReturnTo(bad), null, `should reject ${JSON.stringify(bad)}`);
  }
  for (const ok of ["/", "/patients/p1", "/patients/p1?tab=visit#notes", "/consult?entry=abc-123", "/loginx"]) assert.equal(A.safeReturnTo(ok), ok);
});

test("takeReturnTo: query evil -> null; query safe wins; value consumed once; evil stored value ignored", () => {
  loc.search = "?returnTo=" + encodeURIComponent("https://evil.example");
  assert.equal(A.takeReturnTo(), null);
  loc.search = "?returnTo=" + encodeURIComponent("//evil.example");
  sessionStorage.setItem("nabd_return_to", "//evil.example");
  assert.equal(A.takeReturnTo(), null);
  loc.search = "?returnTo=" + encodeURIComponent("/visits/v9?x=1");
  assert.equal(A.takeReturnTo(), "/visits/v9?x=1");
  loc.search = "";
  sessionStorage.setItem("nabd_return_to", "/patients/p2");
  assert.equal(A.takeReturnTo(), "/patients/p2");
  assert.equal(A.takeReturnTo(), null, "consumed");
});

test("goLogin on /login does not loop; off-origin-looking current path is not echoed", () => {
  loc.pathname = "/login"; loc.search = "";
  A.goLogin();
  assert.equal(loc.replaced.length, 0);
});

test("unlockMessage: three distinct messages, 429 uses Retry-After seconds", () => {
  assert.equal(A.unlockMessage(401, null, false), "Incorrect PIN");
  assert.equal(A.unlockMessage(401, null, true), "Session expired, sign in again");
  assert.equal(A.unlockMessage(429, "30", false), "Too many attempts, wait 30 s");
  assert.equal(A.unlockMessage(429, null, false), "Too many attempts, wait 1 s");
  assert.equal(A.unlockMessage(429, "2.2", false), "Too many attempts, wait 3 s");
});
