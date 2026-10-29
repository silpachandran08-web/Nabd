// FEF-018: step-up middleware, keepSession, blob/text bodies in the shared client; the raw-API gate; the migrated
// owner/setup/staff/reports/operator files carry no API_BASE, authedFetch or hand-parsed res.json().
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createClient, ApiError } from "@nabd/api-client";
import { scan, evaluate } from "../../scripts/check-raw-api.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const json = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const STEP = { type: "https://nabd/problems/step-up-required", title: "Step-up verification required", detail: "needs a code" };

function make(handler, o = {}) {
  const calls = [];
  const c = createClient({
    baseUrl: "http://x/v1", getToken: () => "t1", onAuthFailure: () => { calls.authFail = (calls.authFail ?? 0) + 1; }, retryDelayMs: 1,
    refresh: async () => { calls.refreshed = (calls.refreshed ?? 0) + 1; return false; },
    fetch: async (url, init) => { calls.push({ url, init }); return handler(calls.length, init); }, ...o,
  });
  return { c, calls };
}
const h = (call, name) => call.init.headers[name];

test("step-up: 403 step-up-required asks the verifier, then replays the SAME request once with X-Step-Up-Token", async () => {
  let asked = 0;
  const { c, calls } = make((n) => (n === 1 ? json(403, STEP) : json(204)), { stepUp: async (p) => { asked++; assert.equal(p.title, STEP.title); return "tok-1"; } });
  await c.post("/staff/s1/suspend", { a: 1 });
  assert.equal(asked, 1);
  assert.equal(calls.length, 2);
  assert.equal(h(calls[0], "X-Step-Up-Token"), undefined);
  assert.equal(h(calls[1], "X-Step-Up-Token"), "tok-1");
  assert.equal(calls[1].url, calls[0].url);
  assert.equal(calls[1].init.body, calls[0].init.body);
  assert.equal(h(calls[1], "Idempotency-Key"), h(calls[0], "Idempotency-Key"), "same intent, same key");
});

test("step-up: the replay is made once; a second step-up-required is thrown, not asked again", async () => {
  let asked = 0;
  const { c, calls } = make(() => json(403, STEP), { stepUp: async () => { asked++; return "tok"; } });
  const e = await c.post("/staff/s1/suspend").catch((x) => x);
  assert.ok(e instanceof ApiError);
  assert.equal(e.status, 403);
  assert.equal(asked, 1);
  assert.equal(calls.length, 2);
});

test("step-up: Cancel (null) throws the 403 and sends nothing more; other 403s never ask", async () => {
  let asked = 0;
  const { c, calls } = make(() => json(403, STEP), { stepUp: async () => { asked++; return null; } });
  assert.equal((await c.post("/x").catch((x) => x)).status, 403);
  assert.equal(calls.length, 1);
  const other = make(() => json(403, { type: "https://nabd/problems/forbidden", detail: "no" }), { stepUp: async () => { asked++; return "t"; } });
  assert.equal((await other.c.post("/x").catch((x) => x)).status, 403);
  assert.equal(asked, 1, "only the first client asked");
});

test("step-up: without a handler the 403 is an ordinary ApiError", async () => {
  const { c, calls } = make(() => json(403, STEP));
  assert.equal((await c.post("/x").catch((x) => x)).code, undefined);
  assert.equal(calls.length, 1);
});

test("keepSession: a 401 is the answer, no refresh and no sign-out; without it the session is refreshed and ended", async () => {
  const kept = make(() => json(401, { detail: "wrong code" }));
  const e = await kept.c.post("/auth/mfa/verify", {}, { keepSession: true }).catch((x) => x);
  assert.equal(e.status, 401);
  assert.equal(kept.calls.refreshed, undefined);
  assert.equal(kept.calls.authFail, undefined);
  const plain = make(() => json(401, {}));
  await plain.c.get("/x").catch(() => {});
  assert.equal(plain.calls.refreshed, 1);
  assert.equal(plain.calls.authFail, 1);
});

test("headers option overrides the bearer (owner pending token); as: blob / text return the raw body", async () => {
  const { c, calls } = make((n) => (n === 1 ? json(200, { ok: 1 }) : n === 2 ? new Response("a,b\n1,2", { status: 200 }) : new Response("%PDF", { status: 200 })));
  await c.get("/owners/me/workspaces", { headers: { Authorization: "Bearer pending" } });
  assert.equal(h(calls[0], "Authorization"), "Bearer pending");
  assert.equal(await c.get("/reports/export", { as: "text" }), "a,b\n1,2");
  const blob = await c.get("/pdf", { as: "blob" });
  assert.equal(blob.constructor.name, "Blob");
  assert.equal(await blob.text(), "%PDF");
});

test("raw-api gate: a bad sample fails, a clean one passes, comments do not count, a stale allowlist entry fails", () => {
  const dir = mkdtempSync(join(tmpdir(), "rawapi-"));
  mkdirSync(join(dir, "app"));
  writeFileSync(join(dir, "app", "bad.tsx"), 'const API_BASE = "x";\n');
  writeFileSync(join(dir, "app", "bad2.ts"), "const r = await authedFetch('/a');\n");
  writeFileSync(join(dir, "app", "bad3.ts"), "const b = await res.json();\n");
  writeFileSync(join(dir, "app", "ok.ts"), "// authedFetch and API_BASE were removed\nconst u = process.env.NEXT_PUBLIC_API_BASE_URL;\nconst x = await api.get('/a');\n");
  const hits = scan(join(dir, "app"), dir);
  assert.deepEqual(hits, ["app/bad.tsx", "app/bad2.ts", "app/bad3.ts"]);
  assert.deepEqual(evaluate(hits, ["app/bad.tsx"]).newHits, ["app/bad2.ts", "app/bad3.ts"]);
  assert.deepEqual(evaluate(hits, [...hits, "app/gone.ts"]).stale, ["app/gone.ts"]);
  assert.deepEqual(evaluate([], []), { newHits: [], stale: [] });
});

test("raw-api gate on the real tree: no new file, no stale allowlist entry", () => {
  const allow = JSON.parse(readFileSync(join(root, "scripts", "raw-api-allowlist.json"), "utf8")).files;
  const r = evaluate(scan(join(root, "app"), root), allow);
  assert.deepEqual(r, { newHits: [], stale: [] });
});

const MIGRATED = [
  "app/(clinic)/setup", "app/(clinic)/staff", "app/(clinic)/reports", "app/(clinic)/overview", "app/(owner)/owner", "app/(clinic)/account", "app/(platform)/platform", "app/(clinic)/pharmacy",
  "app/(clinic)/compliance", "app/(auth)/accept-invite", "app/components/setup", "app/components/staff", "app/components/MfaEnrolCard.tsx",
];
test("the migrated screens have no raw fetch, API_BASE, authedFetch or res.json()", () => {
  const hits = scan(join(root, "app"), root).filter((f) => MIGRATED.some((m) => f.startsWith(m)));
  assert.deepEqual(hits, []);
  const allow = JSON.parse(readFileSync(join(root, "scripts", "raw-api-allowlist.json"), "utf8")).files;
  assert.deepEqual(allow.filter((f) => MIGRATED.some((m) => f.startsWith(m))), []);
});

test("operator console: no clinic keys, no fetch( outside the client; clinic code never imports the operator client", async () => {
  const { readdirSync, statSync } = await import("node:fs");
  const files = [];
  (function walk(d) { for (const n of readdirSync(d)) { const p = join(d, n); statSync(p).isDirectory() ? walk(p) : /\.tsx?$/.test(n) && files.push(p); } })(join(root, "app", "(platform)", "platform"));
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    assert.ok(!/nabd_access_token|nabd_refresh_token/.test(src), `${f} touches clinic token keys`);
    assert.ok(!/(^|[^.\w])fetch\(/.test(src.replace(/\/\/.*$/gm, "")), `${f} calls fetch( directly`);
  }
  const clinic = readFileSync(join(root, "app", "lib", "api.ts"), "utf8");
  assert.ok(!/platform/i.test(clinic.replace(/\/\/.*$/gm, "")), "lib/api.ts must not know the operator client");
});
