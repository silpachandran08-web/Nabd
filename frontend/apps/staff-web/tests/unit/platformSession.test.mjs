// PLT-008 / FEF-018: a 401 that survives one refresh clears the OPERATOR session and routes to login with the
// "expired" message; the operator client refreshes through POST /platform/auth/refresh with its own token keys and
// never reads or writes the clinic keys. `npm run -s test:unit`.
import { test, beforeEach, after } from "node:test";
import assert from "node:assert/strict";

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
const realFetch = globalThis.fetch;
after(() => { globalThis.fetch = realFetch; });

const { platformApi, platformLoginPath, storePlatformSession, hasPlatformSession } =
  await import("../../app/platform/platformSession.ts");

let calls;
// Routes requests: the refresh endpoint answers `refresh`, everything else answers `api(authHeader)`.
function mockFetch({ api, refresh }) {
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), auth: init?.headers?.Authorization });
    if (String(url).endsWith("/platform/auth/refresh")) return refresh(JSON.parse(init.body));
    return api(init?.headers?.Authorization);
  };
}
const json = (status, body = {}) => new Response(JSON.stringify(body), { status });
const status = (p) => p.then(() => 200, (e) => e.status);

beforeEach(() => {
  store.clear();
  store.set("nabd_access_token", "clinic-token"); // a clinic session in the same browser
  store.set("nabd_refresh_token", "clinic-refresh");
  storePlatformSession({ accessToken: "old", refreshToken: "r1" }); // also resets the expired flag
});

test("200 passes through with the operator token and keeps the session", async () => {
  mockFetch({ api: () => json(200, { ok: 1 }), refresh: () => json(500) });
  assert.deepEqual(await platformApi.get("/platform/tenants"), { ok: 1 });
  assert.equal(calls[0].auth, "Bearer old");
  assert.ok(hasPlatformSession());
  assert.equal(platformLoginPath(), "/platform/login");
});

test("401 then successful refresh replays with the new token; clinic keys untouched", async () => {
  mockFetch({
    api: (auth) => json(auth === "Bearer new" ? 200 : 401),
    refresh: (body) => { assert.deepEqual(body, { refreshToken: "r1" }); return json(200, { accessToken: "new", refreshToken: "r2" }); },
  });
  assert.equal(await status(platformApi.get("/platform/tenants")), 200);
  assert.equal(store.get("nabd_platform_access_token"), "new");
  assert.equal(store.get("nabd_platform_refresh_token"), "r2");
  assert.equal(store.get("nabd_access_token"), "clinic-token");
  assert.equal(store.get("nabd_refresh_token"), "clinic-refresh");
  assert.ok(calls.every((c) => c.auth !== "Bearer clinic-token"));
  assert.equal(platformLoginPath(), "/platform/login");
});

test("two parallel 401s share one refresh", async () => {
  mockFetch({
    api: (auth) => json(auth === "Bearer new" ? 200 : 401),
    refresh: () => json(200, { accessToken: "new", refreshToken: "r2" }),
  });
  const r = await Promise.all([status(platformApi.get("/platform/a")), status(platformApi.get("/platform/b"))]);
  assert.deepEqual(r, [200, 200]);
  assert.equal(calls.filter((c) => c.url.endsWith("/platform/auth/refresh")).length, 1);
});

test("401 with failed refresh clears the operator session only and marks it expired", async () => {
  mockFetch({ api: () => json(401), refresh: () => json(401) });
  assert.equal(await status(platformApi.get("/platform/tenants")), 401);
  assert.equal(hasPlatformSession(), false);
  assert.equal(store.has("nabd_platform_refresh_token"), false, "both operator tokens removed");
  assert.equal(store.get("nabd_access_token"), "clinic-token", "the clinic session is not touched");
  assert.equal(store.get("nabd_refresh_token"), "clinic-refresh");
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
  // A later parallel call finds no token and must not reset the message.
  await status(platformApi.get("/platform/auth/me"));
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
});

test("never signed in: plain login (no expired message)", async () => {
  store.delete("nabd_platform_access_token"); store.delete("nabd_platform_refresh_token");
  mockFetch({ api: () => json(401), refresh: () => json(200) });
  assert.equal(await status(platformApi.get("/platform/tenants")), 401);
  assert.equal(platformLoginPath(), "/platform/login");
});

test("signing in again resets the expired flag", async () => {
  mockFetch({ api: () => json(401), refresh: () => json(401) });
  await status(platformApi.get("/platform/tenants"));
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
  storePlatformSession({ accessToken: "a", refreshToken: "b" });
  assert.equal(platformLoginPath(), "/platform/login");
});

test("non-401 errors reach the caller as ApiError, session kept", async () => {
  mockFetch({ api: () => json(403, { detail: "no" }), refresh: () => json(500) });
  const e = await platformApi.get("/platform/tenants").catch((x) => x);
  assert.equal(e.status, 403);
  assert.equal(e.detail, "no");
  assert.ok(hasPlatformSession());
});
