// PLT-008: a 401 that survives one refresh clears the platform session and routes to login with the
// "expired" message. `npm run -s test:unit`.
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

const { platformFetch, platformLoginPath, storePlatformSession, hasPlatformSession } =
  await import("../../app/platform/platformSession.ts");

let calls;
// Routes requests: refresh endpoint answers `refresh`, everything else answers `api(authHeader)`.
function mockFetch({ api, refresh }) {
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push(url);
    if (String(url).endsWith("/platform/auth/refresh")) return refresh();
    return api(new Headers(init?.headers).get("Authorization"));
  };
}
const json = (status, body = {}) => new Response(JSON.stringify(body), { status });

beforeEach(() => {
  store.clear();
  storePlatformSession({ accessToken: "old", refreshToken: "r1" }); // also resets the expired flag
});

test("200 passes through and keeps the session", async () => {
  mockFetch({ api: () => json(200), refresh: () => json(500) });
  const res = await platformFetch("/platform/tenants");
  assert.equal(res.status, 200);
  assert.ok(hasPlatformSession());
  assert.equal(platformLoginPath(), "/platform/login");
});

test("401 then successful refresh replays with the new token", async () => {
  mockFetch({
    api: (auth) => json(auth === "Bearer new" ? 200 : 401),
    refresh: () => json(200, { accessToken: "new", refreshToken: "r2" }),
  });
  const res = await platformFetch("/platform/tenants");
  assert.equal(res.status, 200);
  assert.ok(hasPlatformSession());
  assert.equal(platformLoginPath(), "/platform/login");
});

test("401 with failed refresh clears the session and marks it expired", async () => {
  mockFetch({ api: () => json(401), refresh: () => json(401) });
  assert.equal(await platformFetch("/platform/tenants"), null);
  assert.equal(hasPlatformSession(), false);
  assert.equal(store.size, 0, "both tokens removed");
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
  // A later call finds no token: no network request, still routed to the expired message.
  const before = calls.length;
  await new Promise((r) => setTimeout(r, 0)); // let the single-flight refresh reset
  assert.equal(await platformFetch("/platform/auth/me"), null);
  assert.equal(calls.length, before);
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
});

test("never signed in: no request, plain login (no expired message)", async () => {
  store.clear();
  mockFetch({ api: () => json(200), refresh: () => json(200) });
  assert.equal(await platformFetch("/platform/tenants"), null);
  assert.equal(calls.length, 0);
  assert.equal(platformLoginPath(), "/platform/login");
});

test("signing in again resets the expired flag", async () => {
  mockFetch({ api: () => json(401), refresh: () => json(401) });
  await platformFetch("/platform/tenants");
  assert.equal(platformLoginPath(), "/platform/login?expired=1");
  storePlatformSession({ accessToken: "a", refreshToken: "b" });
  assert.equal(platformLoginPath(), "/platform/login");
});

test("non-401 errors are returned to the caller, session kept", async () => {
  mockFetch({ api: () => json(403), refresh: () => json(500) });
  const res = await platformFetch("/platform/tenants");
  assert.equal(res.status, 403);
  assert.ok(hasPlatformSession());
});
