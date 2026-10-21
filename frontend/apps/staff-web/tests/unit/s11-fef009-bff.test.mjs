// FEF-009: BFF contract — CSRF matrix on every verb, cookie attributes, single-flight refresh, logout, header hygiene,
// no-store, no tokens in bodies or logs, platform untouched. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { proxy, session, logout, refreshStep, csrfOk, claimsToken, AT, RT, CSRF, SESSION_HEADER } from "../../app/lib/bff.ts";
import { bffUrl } from "../../app/lib/bffShim.ts";

const ORIGIN = "https://staff.example.in";
const b64u = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const nowS = () => Math.floor(Date.now() / 1000);
// Test-time fake tokens: random signature, never a real key.
const at = (exp = nowS() + 900, extra = {}) => `${b64u({ alg: "RS256" })}.${b64u({ sub: "s1", tenantId: "t1", staffName: "Rana", permissions: ["queue:view"], exp, iat: exp - 900, ...extra })}.${crypto.randomUUID()}`;

function core(routes = {}) {
  const calls = [];
  const f = async (url, init = {}) => {
    const u = new URL(url);
    const h = new Headers(init.headers);
    const body = init.body == null ? null : typeof init.body === "string" ? init.body : await new Response(init.body).text();
    calls.push({ path: u.pathname.replace(/^\/v1/, "") + u.search, method: init.method ?? "GET", headers: h, body });
    const r = routes[`${init.method ?? "GET"} ${u.pathname.replace(/^\/v1/, "")}`];
    if (r) return r({ headers: h, body, calls });
    return Response.json({ ok: true }, { headers: { "cache-control": "max-age=600", "set-cookie": "core=1", connection: "keep-alive" } });
  };
  return { calls, deps: { core: "https://core.internal/v1", fetch: f, origins: [ORIGIN], log: () => {} } };
}
function req(path, { method = "GET", cookies = {}, csrf, origin = ORIGIN, auth, headers = {}, body } = {}) {
  const h = new Headers(headers);
  const jar = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  if (jar) h.set("cookie", jar);
  if (csrf) h.set("x-csrf-token", csrf);
  if (origin) h.set("origin", origin);
  if (auth) h.set("authorization", auth);
  return new Request(`${ORIGIN}${path}`, { method, headers: h, body });
}
const setCookies = (res) => res.headers.getSetCookie();
const cookieOf = (res, name) => setCookies(res).find((c) => c.startsWith(name + "="));
const signed = (cookies = {}) => ({ [AT]: at(), [RT]: "rt-1", [CSRF]: "c-1", ...cookies });

test("CSRF matrix: every mutating verb is refused without/with a wrong token or a foreign/missing Origin, and Core is never called", async () => {
  for (const method of ["POST", "PUT", "PATCH", "DELETE"]) {
    for (const [name, o] of [
      ["missing token", { cookies: signed() }],
      ["wrong token", { cookies: signed(), csrf: "nope" }],
      ["no csrf cookie", { cookies: { [AT]: at(), [RT]: "rt-1" }, csrf: "c-1" }],
      ["foreign origin", { cookies: signed(), csrf: "c-1", origin: "https://evil.example" }],
      ["no origin", { cookies: signed(), csrf: "c-1", origin: null }],
      ["no cookies at all", {}],
    ]) {
      const c = core();
      const res = await proxy(req("/api/v1/queue", { method, ...o }), "/queue", c.deps);
      assert.equal(res.status, 403, `${method} ${name}`);
      assert.equal((await res.json()).code, "CSRF_FAILED", `${method} ${name}`);
      assert.equal(c.calls.length, 0, `${method} ${name}: Core not called`);
    }
    const c = core();
    const ok = await proxy(req("/api/v1/queue", { method, cookies: signed(), csrf: "c-1" }), "/queue", c.deps);
    assert.equal(ok.status, 200, `${method} matching token passes`);
    assert.equal(c.calls.length, 1);
  }
});

test("GET needs no CSRF token; /api/auth pre-session steps pass on Origin alone but not from a foreign Origin", async () => {
  const c = core();
  assert.equal((await proxy(req("/api/v1/queue", { cookies: signed(), origin: null }), "/queue", c.deps)).status, 200);
  assert.equal((await proxy(req("/api/auth/login", { method: "POST", body: "{}" }), "/auth/login", c.deps, true)).status, 200);
  assert.equal((await proxy(req("/api/auth/login", { method: "POST", origin: "https://evil.example" }), "/auth/login", c.deps, true)).status, 403);
  // with a session cookie present the auth step needs the token too
  assert.equal((await proxy(req("/api/auth/pin/change", { method: "POST", cookies: signed() }), "/auth/pin/change", c.deps, true)).status, 403);
  assert.equal(csrfOk(req("/x", { method: "POST", cookies: signed(), csrf: "c-1" }), { origins: [ORIGIN] }), true);
});

test("login: token pair becomes cookies with the required attributes; the body has session claims and no token", async () => {
  const access = at();
  const c = core({ "POST /auth/login": () => Response.json({ accessToken: access, refreshToken: "rt-secret", expiresIn: 900, refreshExpiresIn: 2592000 }) });
  const res = await proxy(req("/api/auth/login", { method: "POST", body: JSON.stringify({ tenantSlug: "a", email: "e", pin: "p" }) }), "/auth/login", c.deps, true);
  assert.equal(res.status, 200);
  const rt = cookieOf(res, RT);
  assert.match(rt, /^__Host-nabd_rt=rt-secret; Path=\/; Max-Age=2592000; Secure; SameSite=Strict; HttpOnly$/);
  assert.match(cookieOf(res, AT), /; Path=\/; Max-Age=900; Secure; SameSite=Strict; HttpOnly$/);
  const csrf = cookieOf(res, CSRF);
  assert.match(csrf, /; Path=\/; Max-Age=2592000; Secure; SameSite=Strict$/, "csrf cookie is readable by the page (double submit)");
  const text = await res.text();
  assert.ok(!text.includes("rt-secret") && !text.includes(access), "no Core token in the body");
  const body = JSON.parse(text);
  assert.deepEqual([body.staffId, body.tenantId, body.permissions, body.name], ["s1", "t1", ["queue:view"], "Rana"]);
  assert.equal(body.refreshToken, undefined);
  assert.equal(body.accessToken.split(".")[2], "", "claims snapshot carries no signature");
  assert.equal(JSON.parse(Buffer.from(body.accessToken.split(".")[0], "base64url")).alg, "none");
  assert.equal(res.headers.get("cache-control"), "no-store");
});

test("login MFA challenge passes through untouched (no cookies)", async () => {
  const c = core({ "POST /auth/login": () => Response.json({ challengeId: "ch1", method: "totp", expiresIn: 300 }) });
  const res = await proxy(req("/api/auth/login", { method: "POST", body: "{}" }), "/auth/login", c.deps, true);
  assert.deepEqual(await res.json(), { challengeId: "ch1", method: "totp", expiresIn: 300 });
  assert.equal(setCookies(res).length, 0);
});

test("expired access cookie: 6 parallel calls refresh once, every call returns data, new cookies set, late old-cookie call reuses the result", async () => {
  let refreshes = 0;
  const fresh = at(nowS() + 900, { tag: "new" });
  const c = core({
    "POST /auth/refresh": async ({ body }) => { refreshes++; assert.equal(JSON.parse(body).refreshToken, "rt-old"); await new Promise((r) => setTimeout(r, 30)); return Response.json({ accessToken: fresh, refreshToken: "rt-new", expiresIn: 900, refreshExpiresIn: 1000 }); },
    "GET /queue": ({ headers }) => headers.get("authorization") === `Bearer ${fresh}` ? Response.json([{ id: 1 }]) : new Response("{}", { status: 401 }),
  });
  const cookies = { [AT]: at(nowS() - 5), [RT]: "rt-old", [CSRF]: "c-1" };
  const all = await Promise.all(Array.from({ length: 6 }, () => proxy(req("/api/v1/queue", { cookies }), "/queue", c.deps)));
  assert.equal(refreshes, 1, "single flight per session");
  for (const r of all) {
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), [{ id: 1 }]);
    assert.match(cookieOf(r, RT), /^__Host-nabd_rt=rt-new; Path=\/; Max-Age=1000;/);
    assert.match(cookieOf(r, CSRF), /^__Host-nabd_csrf=c-1;/, "csrf token kept across refresh");
    assert.equal(r.headers.get(SESSION_HEADER), claimsToken(fresh));
  }
  const late = await proxy(req("/api/v1/queue", { cookies }), "/queue", c.deps);
  assert.equal(late.status, 200);
  assert.equal(refreshes, 1, "a request still carrying the old cookie inside the grace window does not refresh again");
  // a missing access cookie (browser dropped it at Max-Age) behaves the same
  const c2 = core({ "POST /auth/refresh": () => Response.json({ accessToken: fresh, refreshToken: "rt-2", expiresIn: 900 }), "GET /queue": () => Response.json([]) });
  const r2 = await proxy(req("/api/v1/queue", { cookies: { [RT]: "rt-other", [CSRF]: "c" } }), "/queue", c2.deps);
  assert.equal(r2.status, 200);
  assert.equal(c2.calls.filter((x) => x.path === "/auth/refresh").length, 1);
});

test("refresh refused: 401 SESSION_EXPIRED, all three cookies cleared, Core data endpoint not called", async () => {
  const c = core({ "POST /auth/refresh": () => new Response("{}", { status: 401 }) });
  const res = await proxy(req("/api/v1/queue", { cookies: { [AT]: at(nowS() - 5), [RT]: "rt-dead", [CSRF]: "c" } }), "/queue", c.deps);
  assert.equal(res.status, 401);
  assert.equal((await res.json()).code, "SESSION_EXPIRED");
  for (const n of [AT, RT, CSRF]) assert.match(cookieOf(res, n), /Max-Age=0/);
  assert.deepEqual(c.calls.map((x) => x.path), ["/auth/refresh"]);
  // Core down during refresh: 503, cookies kept
  const d = core({ "POST /auth/refresh": () => new Response("", { status: 503 }) });
  const r503 = await proxy(req("/api/v1/queue", { cookies: { [AT]: at(nowS() - 5), [RT]: "rt-503", [CSRF]: "c" } }), "/queue", d.deps);
  assert.equal(r503.status, 503);
  assert.equal(setCookies(r503).length, 0);
});

test("header hygiene: passthrough of Idempotency-Key/X-Request-Id/Accept-Language, no cookie/csrf/hop-by-hop to Core, no-store and no Core cookies back", async () => {
  const c = core();
  const access = at();
  const res = await proxy(req("/api/v1/payments?x=1", { method: "POST", cookies: signed({ [AT]: access }), csrf: "c-1", body: '{"a":1}',
    headers: { "idempotency-key": "k-1", "x-request-id": "rid-1", "accept-language": "ar", "content-type": "application/json", connection: "keep-alive", "x-step-up-token": "su" } }), "/payments?x=1", c.deps);
  const sent = c.calls[0];
  assert.equal(sent.path, "/payments?x=1");
  assert.equal(sent.body, '{"a":1}', "body streamed through");
  assert.equal(sent.headers.get("idempotency-key"), "k-1");
  assert.equal(sent.headers.get("x-request-id"), "rid-1");
  assert.equal(sent.headers.get("accept-language"), "ar");
  assert.equal(sent.headers.get("x-step-up-token"), "su");
  assert.equal(sent.headers.get("authorization"), `Bearer ${access}`);
  for (const h of ["cookie", "x-csrf-token", "connection", "origin"]) assert.equal(sent.headers.get(h), null, h);
  assert.equal(res.headers.get("cache-control"), "no-store");
  assert.equal(res.headers.get("connection"), null);
  assert.equal(setCookies(res).length, 0, "Core's own Set-Cookie never reaches the browser");
});

test("Authorization: claims snapshot is replaced by the cookie token; a real token (owner pending) passes; /auth steps without one get none", async () => {
  const access = at();
  const c = core();
  await proxy(req("/api/v1/queue", { cookies: signed({ [AT]: access }), auth: `Bearer ${claimsToken(access)}` }), "/queue", c.deps);
  assert.equal(c.calls[0].headers.get("authorization"), `Bearer ${access}`);
  await proxy(req("/api/v1/owners/me/workspaces", { cookies: signed({ [AT]: access }), auth: "Bearer pending-1" }), "/owners/me/workspaces", c.deps);
  assert.equal(c.calls[1].headers.get("authorization"), "Bearer pending-1");
  await proxy(req("/api/auth/mfa/verify", { method: "POST", cookies: signed({ [AT]: access }), csrf: "c-1", body: "{}" }), "/auth/mfa/verify", c.deps, true);
  assert.equal(c.calls[2].headers.get("authorization"), null, "login-challenge MFA verify is not turned into a step-up");
});

test("platform token pairs are not converted (console keeps its own auth)", async () => {
  const c = core({ "POST /platform/auth/login": () => Response.json({ accessToken: "pa", refreshToken: "pr", expiresIn: 900 }) });
  const res = await proxy(req("/api/v1/platform/auth/login", { method: "POST", cookies: signed(), csrf: "c-1", body: "{}" }), "/platform/auth/login", c.deps);
  assert.equal(setCookies(res).length, 0);
  assert.equal((await res.json()).refreshToken, "pr");
});

test("GET /api/session: claims without tokens, bootstraps the csrf cookie, 401 NO_SESSION when signed out", async () => {
  const c = core();
  const access = at();
  const res = await session(req("/api/session", { cookies: { [AT]: access, [RT]: "rt-1" } }), c.deps);
  assert.equal(res.status, 200);
  const text = await res.text();
  assert.ok(!text.includes(access) && !text.includes("rt-1"));
  assert.ok(cookieOf(res, CSRF), "csrf cookie issued when missing");
  const none = await session(req("/api/session"), c.deps);
  assert.equal(none.status, 401);
  assert.equal((await none.json()).code, "NO_SESSION");
  assert.ok(cookieOf(none, CSRF), "csrf cookie issued before sign-in too");
  assert.equal(c.calls.length, 0);
});

test("logout: CSRF required; Core logout called with the cookie token; all three cookies cleared; 204", async () => {
  const access = at();
  const c = core({ "POST /auth/logout": () => new Response(null, { status: 204 }) });
  assert.equal((await logout(req("/api/auth/logout", { method: "POST", cookies: signed({ [AT]: access }) }), c.deps)).status, 403);
  assert.equal(c.calls.length, 0);
  const res = await logout(req("/api/auth/logout", { method: "POST", cookies: signed({ [AT]: access }), csrf: "c-1" }), c.deps);
  assert.equal(res.status, 204);
  assert.equal(c.calls[0].path, "/auth/logout");
  assert.equal(c.calls[0].headers.get("authorization"), `Bearer ${access}`);
  for (const n of [AT, RT, CSRF]) assert.match(cookieOf(res, n), /^__Host-nabd_\w+=; Path=\/; Max-Age=0; Secure; SameSite=Strict/);
  // Core unreachable: cookies are still cleared
  const down = { ...c.deps, fetch: async () => { throw new TypeError("down"); } };
  const r2 = await logout(req("/api/auth/logout", { method: "POST", cookies: signed({ [AT]: access }), csrf: "c-1" }), down);
  assert.equal(r2.status, 204);
  assert.equal(setCookies(r2).length, 3);
});

test("/api/auth/refresh rotates with the cookie, never with a body token", async () => {
  const fresh = at();
  const c = core({ "POST /auth/refresh": ({ body }) => { assert.equal(JSON.parse(body).refreshToken, "rt-cookie"); return Response.json({ accessToken: fresh, refreshToken: "rt-next", expiresIn: 900, refreshExpiresIn: 99 }); } });
  const res = await refreshStep(req("/api/auth/refresh", { method: "POST", cookies: { [RT]: "rt-cookie", [CSRF]: "c-1" }, csrf: "c-1", body: JSON.stringify({ refreshToken: "attacker" }) }), c.deps);
  assert.equal(res.status, 200);
  assert.match(cookieOf(res, RT), /rt-next; Path=\/; Max-Age=99;/);
  assert.ok(!(await res.text()).includes("rt-next"));
});

test("logs: route template, status, duration, request id only — never path ids, bodies, cookies or tokens", async () => {
  const lines = [];
  const c = core();
  const access = at();
  await proxy(req("/api/v1/patients/9876543210?q=Rana", { method: "POST", cookies: signed({ [AT]: access }), csrf: "c-1", body: '{"name":"Rana"}', headers: { "x-request-id": "rid-9" } }), "/patients/9876543210?q=Rana", { ...c.deps, log: (l) => lines.push(l) });
  assert.equal(lines.length, 1);
  assert.match(lines[0], /^bff POST \/api\/v1\/\* 200 \d+ms req=rid-9$/);
});

test("Core unreachable on a proxied call: 502 Problem, no-store", async () => {
  const deps = { core: "https://core.internal/v1", fetch: async () => { throw new TypeError("ECONNREFUSED"); }, origins: [ORIGIN], log: () => {} };
  const res = await proxy(req("/api/v1/queue", { cookies: signed() }), "/queue", deps);
  assert.equal(res.status, 502);
  assert.equal(res.headers.get("cache-control"), "no-store");
});

test("shim routing: auth -> /api/auth, rest -> /api/v1, platform and foreign URLs untouched", () => {
  const B = "http://localhost:8080/v1";
  assert.equal(bffUrl(`${B}/auth/login`, B), "/api/auth/login");
  assert.equal(bffUrl(`${B}/auth/logout`, B), "/api/auth/logout");
  assert.equal(bffUrl(`${B}/queue?doctorId=1`, B), "/api/v1/queue?doctorId=1");
  assert.equal(bffUrl(`${B}/platform/auth/login`, B), null);
  assert.equal(bffUrl("https://other.example/v1/queue", B), null);
  assert.equal(bffUrl(`${B}x/queue`, B), null);
});
