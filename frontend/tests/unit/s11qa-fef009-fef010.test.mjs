// S11 QA (independent): FEF-009 BFF and FEF-010 CSP pure logic that must hold together after the merge.
import { test } from "node:test";
import assert from "node:assert/strict";
import { proxy, csrfOk } from "../../app/lib/bff.ts";
import { bffUrl } from "../../app/lib/bffShim.ts";
import { buildCsp, newNonce, apiOrigin, cspHeaderName } from "../../app/security/csp.ts";

const ORIGIN = "https://staff.example.in";

test("every Core URL the cookie-mode shim reroutes is same-origin, so connect-src 'self' covers it", () => {
  const base = "http://localhost:8080/v1";
  const csp = buildCsp(newNonce(), { connect: apiOrigin(base) });
  assert.match(csp, /connect-src 'self'/);
  for (const u of [`${base}/queue`, `${base}/auth/login`, `${base}/patients/registry?q=a`, `${base}/auth/refresh`]) {
    const to = bffUrl(u, base);
    assert.ok(to && to.startsWith("/api/"), `${u} -> ${to}`);
    assert.equal(new URL(to, ORIGIN).origin, ORIGIN);
  }
  assert.equal(bffUrl(`${base}/platform/tenants`, base), null, "platform console stays bearer");
});

test("nonce: 128-bit, unique across 2000 requests, script-src never relaxed in a production build", () => {
  const seen = new Set();
  for (let i = 0; i < 2000; i++) seen.add(newNonce());
  assert.equal(seen.size, 2000);
  assert.equal(Buffer.from([...seen][0], "base64").length, 16);
  const scriptSrc = buildCsp("abc", { dev: false }).split("; ").find((d) => d.startsWith("script-src "));
  assert.equal(scriptSrc, "script-src 'self' 'nonce-abc' 'strict-dynamic'");
  assert.equal(cspHeaderName("enforce"), "Content-Security-Policy");
  assert.equal(cspHeaderName(undefined), "Content-Security-Policy-Report-Only");
  assert.equal(cspHeaderName("ENFORCE"), "Content-Security-Policy-Report-Only", "only the exact value enforces");
});

test("login CSRF: a pre-session /api/auth/login from a foreign or missing Origin is refused before Core", async () => {
  let calls = 0;
  const deps = { core: "http://core/v1", fetch: async () => { calls++; return new Response("{}", { status: 200, headers: { "content-type": "application/json" } }); }, origins: [ORIGIN], log: () => {} };
  for (const origin of ["https://evil.example", null, "null"]) {
    const h = new Headers({ "content-type": "application/json" });
    if (origin) h.set("origin", origin);
    const req = new Request(`${ORIGIN}/api/auth/login`, { method: "POST", headers: h, body: "{}" });
    const res = await proxy(req, "/auth/login", deps, true);
    assert.equal(res.status, 403, String(origin));
    assert.equal((await res.json()).code, "CSRF_FAILED");
  }
  assert.equal(calls, 0);
  // own origin, no session cookie yet: allowed without a token (there is nothing to echo)
  assert.equal(csrfOk(new Request(`${ORIGIN}/api/auth/login`, { method: "POST", headers: { origin: ORIGIN } }), deps, true), true);
  // but once a session cookie exists, the token is required again
  assert.equal(csrfOk(new Request(`${ORIGIN}/api/auth/login`, { method: "POST", headers: { origin: ORIGIN, cookie: "__Host-nabd_rt=r1" } }), deps, true), false);
});

test("a Core response can never plant cookies or CORS grants through the BFF, and is always no-store", async () => {
  const deps = { core: "http://core/v1", origins: [ORIGIN], log: () => {},
    fetch: async () => new Response("[]", { status: 200, headers: { "content-type": "application/json", "set-cookie": "evil=1; Path=/", "access-control-allow-origin": "*", "cache-control": "public, max-age=600" } }) };
  const res = await proxy(new Request(`${ORIGIN}/api/v1/queue`, { headers: { cookie: "__Host-nabd_at=x" } }), "/queue", deps);
  assert.equal(res.headers.get("cache-control"), "no-store");
  assert.equal(res.headers.get("access-control-allow-origin"), null);
  assert.ok(!(res.headers.get("set-cookie") ?? "").includes("evil="));
});
